package com.example.rag.llm;

/**
 * 从逐步累积的 JSON 文本里增量取出某个字符串字段的值。
 *
 * <p>流式模型输出的是"整段 JSON 的一部分"，用户能看的字段（如 nextQuestion、answer）混在
 * 键名、括号与转义符之间，无法直接展示。本类每次接收"到目前为止的全部文本"，返回自上次
 * 调用以来新解码出的字段值增量——调用方只管把增量转发给前端，不必关心 JSON 结构。
 *
 * <p>每次都基于全量文本重新扫描而不是维护游标状态：单次输出只有几 KB，重扫代价可以忽略，
 * 换来的是对"块边界任意切割"的天然免疫（键名被切开、转义序列被切开、中文被切开都只是
 * 扫描到一半自然停下，下一块到来后继续）。
 */
public final class JsonFieldStreamExtractor {
    private final String fieldName;
    private int emitted = 0;

    public JsonFieldStreamExtractor(String fieldName) {
        this.fieldName = fieldName;
    }

    /**
     * @param textSoFar 到目前为止收到的全部流式文本
     * @return 相对上次调用新增的已解码字段值；字段尚未出现或没有新内容时返回空串
     */
    public String accept(String textSoFar) {
        String decoded = decodeFieldValue(textSoFar, fieldName);
        if (decoded == null || decoded.length() <= emitted) return "";
        String delta = decoded.substring(emitted);
        emitted = decoded.length();
        return delta;
    }

    /**
     * 定位字段的字符串值并解码。text 可能在任何位置被截断（流进行中），此时返回已能
     * 解码出的前缀；字段还没出现、键名后面还不是字符串值时返回 null。
     */
    static String decodeFieldValue(String text, String fieldName) {
        String needle = "\"" + fieldName + "\"";
        int from = 0;
        while (true) {
            int keyIndex = text.indexOf(needle, from);
            if (keyIndex < 0) return null;
            // 被转义引号包着的键名（\"field\"）出现在更早的字符串值里时不算真正的键。
            if (keyIndex > 0 && text.charAt(keyIndex - 1) == '\\') {
                from = keyIndex + 1;
                continue;
            }
            int cursor = skipWhitespace(text, keyIndex + needle.length());
            // 冒号检查是区分"键"与"某个字符串值恰巧等于字段名"（如 "status":"answer"）的
            // 唯一手段：值的后面跟的是逗号或收尾，不是冒号。
            if (cursor >= text.length() || text.charAt(cursor) != ':') {
                from = keyIndex + 1;
                continue;
            }
            cursor = skipWhitespace(text, cursor + 1);
            if (cursor >= text.length()) return null;
            if (text.charAt(cursor) != '"') return null;
            return decodeString(text, cursor + 1);
        }
    }

    /** 从开引号之后开始解码，遇到未转义的收尾引号、文本耗尽或不完整的转义序列就停下。 */
    private static String decodeString(String text, int start) {
        StringBuilder decoded = new StringBuilder();
        int index = start;
        while (index < text.length()) {
            char current = text.charAt(index);
            if (current == '"') break;
            if (current != '\\') {
                decoded.append(current);
                index++;
                continue;
            }
            if (index + 1 >= text.length()) break;
            char escape = text.charAt(index + 1);
            switch (escape) {
                case '"' -> { decoded.append('"'); index += 2; }
                case '\\' -> { decoded.append('\\'); index += 2; }
                case '/' -> { decoded.append('/'); index += 2; }
                case 'b' -> { decoded.append('\b'); index += 2; }
                case 'f' -> { decoded.append('\f'); index += 2; }
                case 'n' -> { decoded.append('\n'); index += 2; }
                case 'r' -> { decoded.append('\r'); index += 2; }
                case 't' -> { decoded.append('\t'); index += 2; }
                case 'u' -> {
                    // Unicode 转义（反斜杠 u 加四位十六进制）还没传完（常见于中文逐块到达）时
                    // 停在这里，等下一块凑齐。
                    if (index + 6 > text.length()) return decoded.toString();
                    String hex = text.substring(index + 2, index + 6);
                    try {
                        decoded.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException malformed) {
                        // 畸形转义：宁缺勿错，停在这里；最终以校验过的完整 JSON 为准。
                        return decoded.toString();
                    }
                    index += 6;
                }
                default -> { decoded.append(escape); index += 2; }
            }
        }
        return decoded.toString();
    }

    private static int skipWhitespace(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && Character.isWhitespace(text.charAt(cursor))) cursor++;
        return cursor;
    }
}
