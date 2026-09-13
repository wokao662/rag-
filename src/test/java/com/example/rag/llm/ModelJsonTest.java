package com.example.rag.llm;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void rejectsMalformedJson() {
        // 截断或语法错误：JsonParser 抛 JsonParseException（RuntimeException 子类），
        // 客户端既有 try/catch(RuntimeException) 会转成 IOException 走降级。
        assertThrows(RuntimeException.class, () -> ModelJson.parseObject("{\"goal\":\"未闭合"));
    }
}
