package com.example.rag.recommendation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecommendationValidatorTest {
    private final RecommendationValidator validator = new RecommendationValidator();
    private final Map<String, String> citationIndex = Map.of("K1", "chunk-1", "K2", "chunk-2");

    @Test
    void acceptsValidAnswer() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "answer",
                  "answer": "建议使用分散练习。",
                  "userConstraints": ["每天30分钟"],
                  "recommendations": [
                    {
                      "strategyId": "strategy-distributed-practice",
                      "strategyName": "分散练习",
                      "reason": "适合记单词很快忘的情况",
                      "methodSteps": ["把复习分散到多天"],
                      "sourceIds": ["source-001"],
                      "citations": ["K1"],
                      "caveats": []
                    }
                  ],
                  "followUpQuestions": []
                }
                """).getAsJsonObject();

        RecommendationValidator.Output result = validator.validate(output, citationIndex);

        assertEquals("answer", result.status());
        assertEquals(1, result.recommendations().size());
        assertEquals("strategy-distributed-practice", result.recommendations().get(0).strategyId());
        // ref 编号在校验时映射回真实 chunkId：下游与落库拿到的永远是 chunkId。
        assertEquals("chunk-1", result.recommendations().get(0).citations().get(0));
    }

    @Test
    void acceptsExplainWithoutRecommendations() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "explain",
                  "answer": "间隔学习可以这样落地：今天先过20个新词，明天、第三天、第七天各复习一次……",
                  "recommendations": [],
                  "followUpQuestions": ["要不要我帮你排一个30天复习表？"]
                }
                """).getAsJsonObject();

        RecommendationValidator.Output result = validator.validate(output, citationIndex);

        assertEquals("explain", result.status());
        assertEquals(0, result.recommendations().size());
        assertEquals(1, result.followUpQuestions().size());
    }

    @Test
    void acceptsExplainCarryingTheTaughtStrategy() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "explain",
                  "answer": "关键词助记的具体做法是：给每个单词配一个发音相近的画面……",
                  "recommendations": [
                    {
                      "strategyId": "strategy-keyword-mnemonic",
                      "strategyName": "关键词助记",
                      "reason": "展开讲解时携带的被追问策略",
                      "citations": ["K1"]
                    }
                  ]
                }
                """).getAsJsonObject();

        RecommendationValidator.Output result = validator.validate(output, citationIndex);

        assertEquals("explain", result.status());
        assertEquals(1, result.recommendations().size());
    }

    @Test
    void rejectsCitationOutsideCandidates() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "answer",
                  "answer": "建议使用分散练习。",
                  "recommendations": [
                    {
                      "strategyId": "strategy-distributed-practice",
                      "strategyName": "分散练习",
                      "reason": "适合记单词很快忘的情况",
                      "citations": ["K9"]
                    }
                  ]
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, citationIndex));
    }

    @Test
    void mapsRefCitationCaseInsensitivelyBackToChunkId() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "explain",
                  "answer": "展开讲解。",
                  "recommendations": [
                    {
                      "strategyId": "strategy-distributed-practice",
                      "strategyName": "分散练习",
                      "reason": "r",
                      "citations": ["k1"]
                    }
                  ]
                }
                """).getAsJsonObject();

        RecommendationValidator.Output result = validator.validate(output, citationIndex);

        assertEquals("chunk-1", result.recommendations().get(0).citations().get(0));
    }

    @Test
    void acceptsRawChunkIdCitationForCompatibility() {
        // 模型偶尔绕过 ref 直接输出真实 chunkId：兼容放行，输出仍是 chunkId。
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "explain",
                  "answer": "展开讲解。",
                  "recommendations": [
                    {
                      "strategyId": "strategy-distributed-practice",
                      "strategyName": "分散练习",
                      "reason": "r",
                      "citations": ["chunk-2"]
                    }
                  ]
                }
                """).getAsJsonObject();

        RecommendationValidator.Output result = validator.validate(output, citationIndex);

        assertEquals("chunk-2", result.recommendations().get(0).citations().get(0));
    }

    @Test
    void rejectsTranscriptionErrorInRawChunkId() {
        // 回归 2026-10-04：真实 chunkId 抄错 1 个字符（b91d→b51d）——既不是合法 ref
        // 也不是真实 chunkId，必须拒绝。
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "explain",
                  "answer": "展开讲解。",
                  "recommendations": [
                    {
                      "strategyId": "strategy-distributed-practice",
                      "strategyName": "分散练习",
                      "reason": "r",
                      "citations": ["chunk-11"]
                    }
                  ]
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, citationIndex));
    }

    @Test
    void rejectsNoMatchWithRecommendations() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "no_match",
                  "answer": "没有合适的策略。",
                  "recommendations": [
                    {
                      "strategyId": "strategy-distributed-practice",
                      "strategyName": "分散练习",
                      "reason": "不适合出现在 no_match 中"
                    }
                  ]
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, citationIndex));
    }

    @Test
    void rejectsClarifyWithoutFollowUpQuestions() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "clarify",
                  "answer": "还需要了解一些信息。",
                  "recommendations": []
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, citationIndex));
    }

    @Test
    void rejectsUnknownStatus() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "guess",
                  "answer": "随意回答。",
                  "recommendations": []
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, citationIndex));
    }

    @Test
    void rejectsTooManyRecommendations() {
        JsonObject output = JsonParser.parseString("""
                {
                  "status": "answer",
                  "answer": "推荐过多。",
                  "recommendations": [
                    {"strategyId": "a", "strategyName": "甲", "reason": "r"},
                    {"strategyId": "b", "strategyName": "乙", "reason": "r"},
                    {"strategyId": "c", "strategyName": "丙", "reason": "r"},
                    {"strategyId": "d", "strategyName": "丁", "reason": "r"}
                  ]
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, citationIndex));
    }
}
