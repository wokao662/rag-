package com.example.rag.llm;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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

    private ModelJson() {
    }

    /**
     * 从模型返回内容中解析出唯一的 JSON 对象。
     *
     * @param content 模型 {@code choices[0].message.content} 原文，可能带围栏或前后缀文字
     * @return 解析出的 JSON 对象
     * @throws IllegalArgumentException 内容为空、找不到成对花括号或不是合法 JSON 对象时
     */
    public static JsonObject parseObject(String content) {
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("模型返回内容为空");
        }
        String candidate = stripCodeFence(content).trim();
        int start = candidate.indexOf('{');
        int end = candidate.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("模型返回内容里找不到 JSON 对象：" + content);
        }
        String json = candidate.substring(start, end + 1);
        var element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("模型返回的不是 JSON 对象：" + content);
        }
        return element.getAsJsonObject();
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
