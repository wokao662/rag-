package com.example.rag.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 对推荐模型的输出做结构和一致性校验；引用必须指向真实检索到的 chunk。
 *
 * <p>引用的输入形态是参考资料里的短编号（K1…，由 RecommendationService.annotateRefs 分配）：
 * 长 chunkId 自 2026-10-04 起不再交给模型抄写（实测会抄错单个字符导致整轮作废），
 * 校验时映射回真实 chunkId 落库，下游无感知。
 */
public final class RecommendationValidator {
    private static final Set<String> ALLOWED_STATUS = Set.of("answer", "explain", "clarify", "no_match");
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

    public Output validate(JsonObject output, Map<String, String> citationIndex) {
        String status = requiredString(output, "status");
        if (!ALLOWED_STATUS.contains(status)) {
            throw new IllegalArgumentException("status 只能是 answer、explain、clarify 或 no_match");
        }
        String answer = requiredString(output, "answer");
        List<String> userConstraints = optionalStringList(output, "userConstraints");
        List<Recommendation> recommendations = recommendations(output, citationIndex);
        List<String> followUpQuestions = optionalStringList(output, "followUpQuestions");

        // explain 是对已推荐方法的追问展开：正文在 answer 里，不强制携带推荐卡片。
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

    private static List<Recommendation> recommendations(JsonObject output, Map<String, String> citationIndex) {
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
            List<String> resolved = new ArrayList<>(citations.size());
            for (String citation : citations) {
                resolved.add(resolveCitation(citation, citationIndex));
            }
            result.add(new Recommendation(
                    requiredString(recommendation, "strategyId"),
                    requiredString(recommendation, "strategyName"),
                    requiredString(recommendation, "reason"),
                    optionalStringList(recommendation, "methodSteps"),
                    optionalStringList(recommendation, "sourceIds"),
                    resolved,
                    optionalStringList(recommendation, "caveats")
            ));
        }
        return result;
    }

    /**
     * 把模型给出的引用解析回真实 chunkId：首选 ref 编号（大小写宽容）；
     * 兼容模型直接输出真实 chunkId 的旧形态；都不中时按引用越界拒绝。
     */
    private static String resolveCitation(String citation, Map<String, String> citationIndex) {
        String chunkId = citationIndex.get(citation.toUpperCase(Locale.ROOT));
        if (chunkId == null && citationIndex.containsValue(citation)) {
            chunkId = citation;
        }
        if (chunkId == null) {
            throw new IllegalArgumentException("citations 引用了参考资料之外的条目：" + citation);
        }
        return chunkId;
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
