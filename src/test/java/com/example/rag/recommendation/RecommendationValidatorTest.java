package com.example.rag.recommendation;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecommendationValidatorTest {
    private final RecommendationValidator validator = new RecommendationValidator();
    private final Set<String> candidateChunkIds = Set.of("chunk-1", "chunk-2");

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
                      "citations": ["chunk-1"],
                      "caveats": []
                    }
                  ],
                  "followUpQuestions": []
                }
                """).getAsJsonObject();

        RecommendationValidator.Output result = validator.validate(output, candidateChunkIds);

        assertEquals("answer", result.status());
        assertEquals(1, result.recommendations().size());
        assertEquals("strategy-distributed-practice", result.recommendations().get(0).strategyId());
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
                      "citations": ["chunk-not-retrieved"]
                    }
                  ]
                }
                """).getAsJsonObject();

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, candidateChunkIds));
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

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, candidateChunkIds));
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

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, candidateChunkIds));
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

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, candidateChunkIds));
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

        assertThrows(IllegalArgumentException.class, () -> validator.validate(output, candidateChunkIds));
    }
}
