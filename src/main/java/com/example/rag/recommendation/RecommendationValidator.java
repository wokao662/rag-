package com.example.rag.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 对推荐模型的输出做结构和一致性校验；引用必须指向真实检索到的 chunk。 */
public final class RecommendationValidator {
    private static final Set<String> ALLOWED_STATUS = Set.of("answer", "clarify", "no_match");
    private static final int MAX_RECOMMENDATIONS = 3;

    public record Recommendation(
            String strategyId,
            String strategyName,
            String reason,
            List<String> methodSteps,
            List<String> sourceIds,
            List<String> citations,
            List<String> caveats
    ) {
    }

    public record Output(
            String status,
            String answer,
            List<String> userConstraints,
            List<Recommendation> recommendations,
            List<String> followUpQuestions
    ) {
    }

    public Output validate(JsonObject output, Set<String> candidateChunkIds) {
        String status = requiredString(output, "status");
        if (!ALLOWED_STATUS.contains(status)) {
            throw new IllegalArgumentException("status 只能是 answer、clarify 或 no_match");
        }
        String answer = requiredString(output, "answer");
        List<String> userConstraints = optionalStringList(output, "userConstraints");
        List<Recommendation> recommendations = recommendations(output, candidateChunkIds);
        List<String> followUpQuestions = optionalStringList(output, "followUpQuestions");

        if ("answer".equals(status) && recommendations.isEmpty()) {
            throw new IllegalArgumentException("answer 状态必须包含至少一条推荐");
        }
        if ("clarify".equals(status) && followUpQuestions.isEmpty()) {
            throw new IllegalArgumentException("clarify 状态必须提供追问");
        }
        if ("no_match".equals(status) && !recommendations.isEmpty()) {
            throw new IllegalArgumentException("no_match 状态不得包含推荐");
        }

        return new Output(status, answer, List.copyOf(userConstraints),
                List.copyOf(recommendations), List.copyOf(followUpQuestions));
    }

    private static List<Recommendation> recommendations(JsonObject output, Set<String> candidateChunkIds) {
        JsonElement value = output.get("recommendations");
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException("recommendations 必须是数组");
        }
        JsonArray array = value.getAsJsonArray();
        if (array.size() > MAX_RECOMMENDATIONS) {
            throw new IllegalArgumentException("最多推荐 " + MAX_RECOMMENDATIONS + " 个策略");
        }
        List<Recommendation> result = new ArrayList<>();
        for (JsonElement item : array) {
            if (!item.isJsonObject()) {
                throw new IllegalArgumentException("recommendations 只能包含对象");
            }
            JsonObject recommendation = item.getAsJsonObject();
            List<String> citations = optionalStringList(recommendation, "citations");
            for (String citation : citations) {
                if (!candidateChunkIds.contains(citation)) {
                    throw new IllegalArgumentException("citations 引用了检索结果之外的 chunkId：" + citation);
                }
            }
            result.add(new Recommendation(
                    requiredString(recommendation, "strategyId"),
                    requiredString(recommendation, "strategyName"),
                    requiredString(recommendation, "reason"),
                    optionalStringList(recommendation, "methodSteps"),
                    optionalStringList(recommendation, "sourceIds"),
                    citations,
                    optionalStringList(recommendation, "caveats")
            ));
        }
        return result;
    }

    private static String requiredString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                || value.getAsString().isBlank()) {
            throw new IllegalArgumentException("缺少非空字段 " + name);
        }
        return value.getAsString().trim();
    }

    private static List<String> optionalStringList(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return List.of();
        if (!value.isJsonArray()) {
            throw new IllegalArgumentException(name + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>();
        for (JsonElement item : value.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                    || item.getAsString().isBlank()) {
                throw new IllegalArgumentException(name + " 只能包含非空字符串");
            }
            result.add(item.getAsString().trim());
        }
        return result;
    }
}
