package com.example.rag.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把模型返回的自由文本稳妥地解析成一个 JSON 对象。
 *
 * <p>存在理由：三个客户端（{@code UserProfileExtractor}、{@code ConversationalProfileAgent}、
 * {@code RecommendationChatClient}）此前都靠请求里的 {@code response_format: json_object}
 * 让服务端强制模型只吐 JSON。实测发现 SiliconFlow 上 {@code Qwen/Qwen3-32B} 的
 * json_object 结构化模式在部分时段严重劣化——11 个 token 的输出也要 17~25 秒，
 * 叠加思维链后逼近 40 秒，直接击穿客户端 30 秒读超时，导致抽取、决策、推荐三路全线超时。
 * 去掉 json_object、只靠 system prompt 约束后，同样的输出降到 1 秒级。
 *
 * <p>代价是失去服务端的 JSON 硬保证：模型偶尔可能把 JSON 包进 Markdown 代码块，
 * 或在前后夹带一句解释。这里统一做兜底——剥掉 ``` 围栏、截取首个 '{' 到末个 '}' 的子串再解析。
 * 解析不出来时抛 {@link IllegalArgumentException}，交由各客户端既有的 try/catch 转成
 * IOException 走各自的降级路径（抽取记失败、决策回退追问、推荐回退），不会因为一次畸形输出而崩。
 */
public final class ModelJson {

    /** 结尾处的完整字符串（键的候选）：形如 {@code "key"}。 */
    private static final Pattern TRAILING_STRING = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"$");

    private ModelJson() {
    }

    /**
     * 从模型返回内容中解析出唯一的 JSON 对象。
     *
     * <p>解析前先剥尾随逗号（结构完整但顶在 {@code }} / {@code ]} 前的逗号，LLM 高频形态）：
     * 必须在解析之前剥离——实测宽松解析器会把 {@code [1,,]} 这类残尾“接受”成带幻影 null
     * 的数组，幻影 null 到下游 {@code getAsString()} 会炸，比解析失败更坏。
     *
     * <p>接着按原样解析；失败时尝试“截断抢救”：模型顶到 max_tokens 时输出会在任意位置
     * 断掉（finish_reason=length），已经写完整的字段与推荐仍有业务价值，不该整轮作废。
     * 抢救只回退到最后一个语法安全点并补全括号，不猜测缺失内容；修不出有效对象时
     * 仍然抛 {@link IllegalArgumentException}，由调用方按畸形输出走降级。
     *
     * @param content 模型 {@code choices[0].message.content} 原文，可能带围栏或前后缀文字
     * @return 解析出的 JSON 对象（可能来自截断抢救）
     * @throws IllegalArgumentException 内容为空、找不到起始花括号、且无法解析或抢救时
     */
    public static JsonObject parseObject(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模型返回内容为空");
        }
        String candidate = stripCodeFence(content).trim();
        int start = candidate.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("模型返回内容里找不到 JSON 对象：" + content);
        }
        // 尾随逗号修复前置（多遍剥到不动点）：合法 JSON 中 } 与 ] 之前不存在逗号，剥掉是恒等
        // 操作；但它必须发生在解析之前——宽松解析器会把 [1,,] 这类残尾接受成 [1,null,null]
        // 的幻影元素，幻影 null 到下游 getAsString() 会炸，比解析失败更坏。
        String full = stripTrailingCommas(candidate.substring(start));
        int end = candidate.lastIndexOf('}');
        // head 是“切到最后一个右花括号”的版本：模型在 JSON 后夹带说明文字时靠它剥掉尾巴。
        // 但截断时“最后一个右花括号”之后还可能有写了一半的结构（含完整字段），
        // 所以 head 修不出来时要退回全长 full 再试，抢救才能尽量多救。
        String head = end > start ? stripTrailingCommas(candidate.substring(start, end + 1)) : full;

        JsonObject parsed = tryParse(head);
        if (parsed != null) {
            return parsed;
        }
        if (!head.equals(full)) {
            JsonObject parsedFull = tryParse(full);
            if (parsedFull != null) {
                return parsedFull;
            }
        }
        JsonObject salvaged = salvageAndParse(full);
        if (salvaged == null && !head.equals(full)) {
            salvaged = salvageAndParse(head);
        }
        if (salvaged != null) {
            return salvaged;
        }
        String keep = full.length() <= 120 ? full : full.substring(full.length() - 120);
        throw new IllegalArgumentException("模型返回的 JSON 不完整或语法错误（可能被截断），尾部：" + keep);
    }

    /** 抢救并解析：修不出来或修出空对象（无有效字段）时返回 null。 */
    private static JsonObject salvageAndParse(String json) {
        String repaired = salvageTruncated(json);
        if (repaired == null) {
            return null;
        }
        JsonObject salvaged = tryParse(repaired);
        // 空对象视为抢救失败：模型还没写任何有效字段就断了，交回去只会误导业务。
        return salvaged != null && salvaged.size() > 0 ? salvaged : null;
    }

    /**
     * 反复剥掉顶在 {@code }} 或 {@code ]} 前的尾随逗号（如 {@code {"a":1,}}、{@code [1,2,]}、
     * {@code [1,,]}），直到不动点。合法 JSON 中 } 与 ] 之前不存在逗号，剥掉不改变任何合法语义；
     * 字符串内部的逗号原样保留。单遍只会剥连续逗号里的最后一颗（前一颗的“下一颗仍是逗号”
     * 使其暂被保留），所以 {@code ,,]} 必须迭代剥离——每遍至少少一个字符，必然终止。
     */
    static String stripTrailingCommas(String json) {
        String current = json;
        while (true) {
            String stripped = stripTrailingCommasOnce(current);
            if (stripped.equals(current)) {
                return current;
            }
            current = stripped;
        }
    }

    /** 单遍剥离：仅丢弃“下一个非空白字符是 } 或 ]”的逗号；字符串内部的逗号原样保留。 */
    private static String stripTrailingCommasOnce(String json) {
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == ',') {
                // 向后跳过空白：紧跟 } 或 ] 的逗号丢弃，其余逗号原样保留。
                int next = i + 1;
                while (next < json.length() && Character.isWhitespace(json.charAt(next))) {
                    next++;
                }
                if (next < json.length() && (json.charAt(next) == '}' || json.charAt(next) == ']')) {
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** 尝试解析为 JSON 对象；任何语法问题都返回 null，由调用方决定抢救或降级。 */
    private static JsonObject tryParse(String json) {
        try {
            var element = JsonParser.parseString(json);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    /**
     * 抢救被截断的 JSON：回退到最后一个“语法安全点”（完整字符串/闭合括号之后），
     * 剥掉悬空的逗号、冒号和没有值的键，再按未闭合的括号栈逆序补全闭合符。
     * 只要拼接结果是合法 JSON 对象，调用方就能拿到截断前已经写完整的部分。
     */
    static String salvageTruncated(String json) {
        int safeEnd = lastSafeEnd(json);
        if (safeEnd <= 1) {
            return null;
        }
        String trimmed = trimDanglingTail(json.substring(0, safeEnd));
        if (trimmed.length() <= 1 || trimmed.charAt(0) != '{') {
            return null;
        }
        StringBuilder repaired = new StringBuilder(trimmed);
        String open = openStack(trimmed);
        for (int i = open.length() - 1; i >= 0; i--) {
            repaired.append(open.charAt(i) == '{' ? '}' : ']');
        }
        return repaired.toString();
    }

    /**
     * 扫描正文，返回最后一个“语法安全”截断位（开区间：截到该下标为止）：
     * 只认完整字符串、{@code }}、{@code ]} 三种结尾——数字或字面量写一半时不算安全，
     * 宁可再往前回退一个值，也不制造语义残缺的数字。
     */
    private static int lastSafeEnd(String json) {
        boolean inString = false;
        boolean escaped = false;
        int safeEnd = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                    safeEnd = i + 1;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '}' || c == ']') {
                safeEnd = i + 1;
            }
        }
        return safeEnd;
    }

    /** 反复剥掉结尾的悬空内容：逗号、单独的冒号、以及后面没有值的键字符串。 */
    private static String trimDanglingTail(String prefix) {
        String s = prefix.stripTrailing();
        while (true) {
            if (s.endsWith(":") || s.endsWith(",")) {
                s = s.substring(0, s.length() - 1).stripTrailing();
                continue;
            }
            Matcher key = TRAILING_STRING.matcher(s);
            if (key.find()) {
                String before = s.substring(0, key.start()).stripTrailing();
                if (before.endsWith(",") || before.endsWith("{") || before.endsWith("[")) {
                    s = before;
                    continue;
                }
            }
            return s;
        }
    }

    /** 未闭合括号栈（{@code {} 与 {@code [}），用于给抢救结果补全闭合符。 */
    private static String openStack(String json) {
        StringBuilder stack = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                stack.append(c);
            } else if ((c == '}' || c == ']') && stack.length() > 0) {
                stack.setLength(stack.length() - 1);
            }
        }
        return stack.toString();
    }

    /** 剥掉可能包裹整段输出的 ```json ... ``` 或 ``` ... ``` 围栏。 */
    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        // 去掉起始围栏行（可能是 ```json 或 ```），再去掉结尾的 ```。
        int firstNewline = trimmed.indexOf('\n');
        String body = firstNewline >= 0 ? trimmed.substring(firstNewline + 1) : trimmed.substring(3);
        int lastFence = body.lastIndexOf("```");
        if (lastFence >= 0) {
            body = body.substring(0, lastFence);
        }
        return body;
    }
}
