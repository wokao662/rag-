package com.example.rag.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 流式增量提取：模型输出在任何位置被任意切开，都不能让用户看到的文本错位、重复或缺字。
 * 因此每个用例要么遍历全部切分点，要么盯住一类历史上最易错的边界（转义、Unicode、同名值）。
 */
class JsonFieldStreamExtractorTest {

    @Test
    void extractsWholeValueWhenFieldComplete() {
        JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("nextQuestion");
        assertEquals("你好，我们可以聊聊你的目标。",
                extractor.accept("{\"action\":\"ask\",\"nextQuestion\":\"你好，我们可以聊聊你的目标。\"}"));
        // 再喂后续内容（模型继续输出其他字段）不应该再产出任何增量。
        assertEquals("", extractor.accept(
                "{\"action\":\"ask\",\"nextQuestion\":\"你好，我们可以聊聊你的目标。\",\"reason\":\"ok\"}"));
    }

    @Test
    void survivesEveryPossibleChunkBoundary() {
        String text = "{\"action\":\"ask\",\"nextQuestion\":\"先把\\\"四级听力\\\"拆成两周目标，\\n每天20分钟够吗？\",\"reason\":\"r\"}";
        String expected = "先把\"四级听力\"拆成两周目标，\n每天20分钟够吗？";
        for (int split = 0; split <= text.length(); split++) {
            JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("nextQuestion");
            StringBuilder seen = new StringBuilder();
            seen.append(extractor.accept(text.substring(0, split)));
            seen.append(extractor.accept(text));
            assertEquals(expected, seen.toString(), "切分点 " + split + " 处产出错位");
        }
    }

    @Test
    void decodesUnicodeEscapesAcrossChunkBoundary() {
        // 中文以 Unicode 转义到达（\u4e2d\u6587 即"中文"），且第二个转义恰好被切成两半。
        String text = "{\"nextQuestion\":\"\\u4e2d\\u6587\"}";
        String half = text.substring(0, text.indexOf("\\u6587") + 3);
        JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("nextQuestion");
        assertEquals("中", extractor.accept(half));
        assertEquals("文", extractor.accept(text));
    }

    @Test
    void ignoresFieldNameThatAppearsAsAnotherFieldsValue() {
        // "status":"answer" 里的 "answer" 是值不是键；误判会把引号后当成字段值，整段错位。
        String text = "{\"status\":\"answer\",\"answer\":\"真正的回答\"}";
        JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("answer");
        assertEquals("真正的回答", extractor.accept(text));
    }

    @Test
    void skipsEscapedFieldNameInsideEarlierString() {
        String text = "{\"reason\":\"这里提了 \\\"answer\\\" 这个词\",\"answer\":\"最终回答\"}";
        JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("answer");
        assertEquals("最终回答", extractor.accept(text));
    }

    @Test
    void emitsAsSoonAsValueStarts() {
        JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("nextQuestion");
        assertEquals("", extractor.accept("{\"action\":\"ask\",\"nextQuestion\":"));
        assertEquals("", extractor.accept("{\"action\":\"ask\",\"nextQuestion\": \""));
        assertEquals("你", extractor.accept("{\"action\":\"ask\",\"nextQuestion\": \"你"));
        assertEquals("好", extractor.accept("{\"action\":\"ask\",\"nextQuestion\": \"你好"));
    }

    @Test
    void stopsBeforeIncompleteEscapeThenResumes() {
        JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("nextQuestion");
        String upToBackslash = "{\"nextQuestion\":\"第一行\\";
        assertEquals("第一行", extractor.accept(upToBackslash));
        assertEquals("\n第二行", extractor.accept(upToBackslash + "n第二行"));
    }
}
