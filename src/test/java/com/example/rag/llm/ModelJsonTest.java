package com.example.rag.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模型输出兜底解析：三个客户端去掉 response_format:json_object 后，JSON 的稳健性全靠这里，
 * 因此围栏、前后缀、畸形输出都必须有明确行为。
 */
class ModelJsonTest {

    @Test
    void parsesPlainJsonObject() {
        JsonObject result = ModelJson.parseObject("{\"goal\":\"四级听力\",\"level\":\"初级\"}");
        assertEquals("四级听力", result.get("goal").getAsString());
        assertEquals("初级", result.get("level").getAsString());
    }

    @Test
    void stripsJsonCodeFence() {
        String content = "```json\n{\"ok\":true}\n```";
        JsonObject result = ModelJson.parseObject(content);
        assertTrue(result.get("ok").getAsBoolean());
    }

    @Test
    void stripsBareCodeFence() {
        String content = "```\n{\"ok\":true}\n```";
        assertTrue(ModelJson.parseObject(content).get("ok").getAsBoolean());
    }

    @Test
    void extractsObjectSurroundedByProse() {
        String content = "好的，这是结果：{\"goal\":\"数学期末\"} 希望对你有帮助。";
        assertEquals("数学期末", ModelJson.parseObject(content).get("goal").getAsString());
    }

    @Test
    void keepsNestedObjectIntact() {
        String content = "前缀 {\"episodeDecision\":{\"action\":\"new\",\"label\":\"四级听力\"},\"updates\":{}} 后缀";
        JsonObject result = ModelJson.parseObject(content);
        assertEquals("new", result.getAsJsonObject("episodeDecision").get("action").getAsString());
        assertTrue(result.get("updates").isJsonObject());
    }

    @Test
    void rejectsBlankContent() {
        assertThrows(IllegalArgumentException.class, () -> ModelJson.parseObject("   "));
        assertThrows(IllegalArgumentException.class, () -> ModelJson.parseObject(null));
    }

    @Test
    void rejectsContentWithoutBraces() {
        assertThrows(IllegalArgumentException.class, () -> ModelJson.parseObject("这里没有任何JSON"));
    }

    @Test
    void rejectsTruncationWithoutSalvageableContent() {
        // 截断太早，抢救只能得到空对象（无业务价值），仍按畸形输出抛 IllegalArgumentException，
        // 由三路客户端统一转成 MalformedOutputException（带 finish_reason 诊断）走降级。
        assertThrows(IllegalArgumentException.class, () -> ModelJson.parseObject("{\"goal\":\"未闭合"));
    }

    @Test
    void repairsTruncationAtArrayElement() {
        // 模拟推荐被 max_tokens 截断：第二个推荐写到一半。第一个推荐完整，
        // 第二个里已写完的字段（strategyId）也一并救回，残缺字段丢弃。
        String content = "{\"status\":\"answer\",\"answer\":\"好的\","
                + "\"recommendations\":[{\"strategyId\":\"a\",\"reason\":\"r1\"},{\"strategyId\":\"b\",\"rea";
        JsonObject result = ModelJson.parseObject(content);
        assertEquals("answer", result.get("status").getAsString());
        assertEquals(2, result.getAsJsonArray("recommendations").size());
        assertEquals("a", result.getAsJsonArray("recommendations").get(0)
                .getAsJsonObject().get("strategyId").getAsString());
        assertEquals("b", result.getAsJsonArray("recommendations").get(1)
                .getAsJsonObject().get("strategyId").getAsString());
    }

    @Test
    void repairsTruncationMidString() {
        // 截断落在半个字符串里：只保留之前写完的字段，残缺字段整体丢弃。
        JsonObject result = ModelJson.parseObject("{\"goal\":\"四级\",\"notes\":\"还没写完的半句话");
        assertEquals("四级", result.get("goal").getAsString());
        assertFalse(result.has("notes"));
    }

    @Test
    void repairsTruncationDanglingKey() {
        // 截断悬在“键刚写完、值还没写”的位置：悬空键与逗号一并剥掉。
        assertEquals(1, ModelJson.parseObject("{\"a\":1,\"b\":").get("a").getAsInt());
    }

    @Test
    void repairsTrailingCommasInObjectsAndArrays() {
        // 完整但带尾随逗号的 JSON（LLM 高频形态）：对象与数组里的尾随逗号都要剥掉。
        JsonObject result = ModelJson.parseObject("{\"a\":[1,2,],\"b\":{\"c\":\"x\",},}");
        assertEquals(2, result.getAsJsonArray("a").size());
        assertEquals(1, result.getAsJsonArray("a").get(0).getAsInt());
        assertEquals(2, result.getAsJsonArray("a").get(1).getAsInt());
        assertEquals("x", result.getAsJsonObject("b").get("c").getAsString());
    }

    @Test
    void trailingCommaRepairKeepsCommasInsideStrings() {
        // 字符串内部的逗号（含结尾逗号）不得被误剥。
        JsonObject result = ModelJson.parseObject("{\"note\":\"a,b,\",\"n\":1,}");
        assertEquals("a,b,", result.get("note").getAsString());
        assertEquals(1, result.get("n").getAsInt());
    }

    @Test
    void trailingCommaRepairHandlesEscapedQuotesInsideStrings() {
        JsonObject result = ModelJson.parseObject("{\"say\":\"he said \\\"hi\\\",\",\"n\":2,}");
        assertEquals("he said \"hi\",", result.get("say").getAsString());
    }

    @Test
    void repairsConsecutiveTrailingCommas() {
        // `,,]` 需要连剥两遍；修复必须停在 [1]——宽松解析器会把残尾吞成 [1,null,null]，
        // 幻影 null 到下游 getAsString() 会炸，此用例就是防它的回归。
        JsonObject result = ModelJson.parseObject("{\"a\":[1,,]}");
        assertEquals(1, result.getAsJsonArray("a").size());
        assertEquals(1, result.getAsJsonArray("a").get(0).getAsInt());
    }

    @Test
    void repairsUnescapedQuotesInsideStringValues() {
        // 2026-10-07 真实事故形态：推荐模型在 answer 里用半角引号强调短语且未转义
        // （finish_reason=stop、结构完整闭合、唯独引号非法）。截断抢救治不了中段畸形，
        // 靠引号修复把内容引号转义后解析；引号在值里保真保留。
        String content = "{\"status\":\"answer\",\"answer\":\"你这不是不努力，而是拿到题后判断不出\"该用哪条路\"。"
                + "接下来先练一件事：\"审题\"。\"}";
        JsonObject result = ModelJson.parseObject(content);
        assertEquals("answer", result.get("status").getAsString());
        assertEquals("你这不是不努力，而是拿到题后判断不出\"该用哪条路\"。接下来先练一件事：\"审题\"。",
                result.get("answer").getAsString());
    }

    @Test
    void quoteRepairIsIdentityOnLegalJson() {
        // 合法 JSON 里字符串的结束引号后必然是结构位，修复器不得改动任何字符（恒等操作）。
        String legal = "{\"a\":\"普通\",\"b\":1,\"c\":[\"x\",\"y\"],\"d\":{\"e\":\"\"}}";
        assertEquals(legal, ModelJson.repairUnescapedQuotes(legal));
    }

    @Test
    void quoteRepairKeepsProperlyEscapedQuotesIntact() {
        // 已正确转义的 \" 是合法内容引号：解析保真，修复器对它也恒等，不得二次转义。
        String content = "{\"say\":\"he said \\\"hi\\\"\",\"n\":1}";
        assertEquals(content, ModelJson.repairUnescapedQuotes(content));
        assertEquals("he said \"hi\"", ModelJson.parseObject(content).get("say").getAsString());
    }

    @Test
    void repairsUnescapedQuotesCombinedWithTruncation() {
        // 中段引号与尾部截断并存：先修引号再抢救，已写完整的字段照样救回，残缺字段丢弃。
        String content = "{\"answer\":\"先做\"审题\"练习，\",\"notes\":\"没写完";
        JsonObject result = ModelJson.parseObject(content);
        assertEquals("先做\"审题\"练习，", result.get("answer").getAsString());
        assertFalse(result.has("notes"));
    }

    @Test
    void repairsUnescapedQuotesAfterColonContent() {
        // 内容里带冒号再接强调引号：判定依据是引号后继字符，而不是内容里有什么。
        JsonObject result = ModelJson.parseObject("{\"x\":\"他说:\"走\"。\"}");
        assertEquals("他说:\"走\"。", result.get("x").getAsString());
    }
}
