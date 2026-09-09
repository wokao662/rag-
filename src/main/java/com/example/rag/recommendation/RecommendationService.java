package com.example.rag.recommendation;

import com.example.rag.observability.ModelCallLogger;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** 编排完整推荐链路：画像生成检索文本、Qdrant 召回、聊天模型生成、Java 校验。 */
@Service
public class RecommendationService {
    private static final int RETRIEVAL_LIMIT = 5;

    private final EmbeddingClient embeddingClient;
    private final QdrantClient qdrantClient;
    private final RecommendationChatClient chatClient;
    private final ProfileQueryBuilder queryBuilder;
    private final RecommendationValidator validator;
    private final ModelCallLogger callLogger;
    private final AtomicBoolean collectionChecked = new AtomicBoolean();

    public RecommendationService(
            EmbeddingClient embeddingClient,
            QdrantClient qdrantClient,
            RecommendationChatClient chatClient,
            ProfileQueryBuilder queryBuilder,
            RecommendationValidator validator,
            ModelCallLogger callLogger
    ) {
        this.embeddingClient = embeddingClient;
        this.qdrantClient = qdrantClient;
        this.chatClient = chatClient;
        this.queryBuilder = queryBuilder;
        this.validator = validator;
        this.callLogger = callLogger;
    }

    public RecommendationResult recommend(JsonObject profile, UUID userId, UUID conversationId) {
        String queryText = queryBuilder.build(profile);
        long start = System.currentTimeMillis();

        JsonArray knowledge;
        try {
            ensureCollection();
            List<Float> queryVector = embeddingClient.embedQuery(queryText);
            JsonArray hits = qdrantClient.search(queryVector, RETRIEVAL_LIMIT);
            knowledge = mergeKnowledge(buildKnowledge(hits), expandByStrategy(hits));
        } catch (IOException error) {
            logCall(userId, conversationId, queryText, null, start, "failed", error.getMessage());
            throw new RecommendationUnavailableException("策略检索服务暂时不可用", error);
        }

        if (knowledge.size() == 0) {
            RecommendationResult empty = new RecommendationResult(
                    "no_match", "知识库中还没有可推荐的学习策略，请稍后再试。",
                    queryText, List.of(), List.of(), List.of());
            logCall(userId, conversationId, queryText, outputOf(empty), start, "success", null);
            return empty;
        }

        JsonObject raw;
        try {
            raw = chatClient.generate(profile, queryText, knowledge);
        } catch (IOException error) {
            logCall(userId, conversationId, queryText, null, start, "failed", error.getMessage());
            throw new RecommendationUnavailableException("推荐生成服务暂时不可用", error);
        }

        RecommendationValidator.Output output;
        try {
            output = validator.validate(raw, candidateChunkIds(knowledge));
        } catch (IllegalArgumentException error) {
            logCall(userId, conversationId, queryText, raw, start, "failed", error.getMessage());
            throw new RecommendationUnavailableException("推荐结果未通过格式校验", error);
        }
        RecommendationResult result = new RecommendationResult(output.status(), output.answer(), queryText,
                output.userConstraints(), output.recommendations(), output.followUpQuestions());
        logCall(userId, conversationId, queryText, outputOf(result), start, "success", null);
        return result;
    }

    private void logCall(UUID userId, UUID conversationId, String queryText, JsonObject output,
                         long start, String status, String errorMessage) {
        JsonObject input = new JsonObject();
        input.addProperty("queryText", queryText);
        callLogger.log(userId, conversationId, "recommend", RecommendationChatClient.MODEL,
                input, output, System.currentTimeMillis() - start, status, errorMessage);
    }

    private static JsonObject outputOf(RecommendationResult result) {
        JsonObject output = new JsonObject();
        output.addProperty("status", result.status());
        JsonArray strategies = new JsonArray();
        result.recommendations().forEach(recommendation -> strategies.add(recommendation.strategyId()));
        output.add("strategyIds", strategies);
        return output;
    }

    private void ensureCollection() throws IOException {
        if (collectionChecked.compareAndSet(false, true)) {
            qdrantClient.ensureCollection(EmbeddingClient.DIMENSION);
        }
    }

    /** 把召回命中策略的全部 chunk 拉回，避免模型只看到定义片段而缺少实施步骤。 */
    private JsonArray expandByStrategy(JsonArray hits) throws IOException {
        List<String> strategyIds = new ArrayList<>();
        for (JsonElement element : hits) {
            String strategyId = element.getAsJsonObject().getAsJsonObject("payload")
                    .get("strategyId").getAsString();
            if (!strategyIds.contains(strategyId)) strategyIds.add(strategyId);
        }
        return strategyIds.isEmpty() ? new JsonArray() : qdrantClient.findByStrategyIds(strategyIds);
    }

    /** 合并补齐的 chunk：已召回的保留相似度分数，补齐的不重复、不带分数。 */
    static JsonArray mergeKnowledge(JsonArray knowledge, JsonArray expandedPoints) {
        Set<String> existing = candidateChunkIds(knowledge);
        for (JsonElement element : expandedPoints) {
            JsonObject point = element.getAsJsonObject();
            String chunkId = point.get("id").getAsString();
            if (existing.add(chunkId)) {
                knowledge.add(toKnowledgeItem(point, false));
            }
        }
        return knowledge;
    }

    private static JsonArray buildKnowledge(JsonArray hits) {
        JsonArray knowledge = new JsonArray();
        for (JsonElement element : hits) {
            knowledge.add(toKnowledgeItem(element.getAsJsonObject(), true));
        }
        return knowledge;
    }

    private static JsonObject toKnowledgeItem(JsonObject hit, boolean includeScore) {
        JsonObject payload = hit.getAsJsonObject("payload");
        JsonObject item = new JsonObject();
        item.addProperty("chunkId", hit.get("id").getAsString());
        if (includeScore) {
            item.addProperty("retrievalScore", hit.get("score").getAsDouble());
        }
        item.addProperty("strategyId", payload.get("strategyId").getAsString());
        item.addProperty("strategyName", payload.get("strategyName").getAsString());
        item.addProperty("chunkType", payload.get("chunkType").getAsString());
        item.addProperty("content", sanitize(payload.get("text").getAsString()));
        item.add("sourceIds", payload.get("sourceIds").deepCopy());
        return item;
    }

    private static String sanitize(String text) {
        String cleaned = text.replace("```", "''' ").replace("---", "___");
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private static Set<String> candidateChunkIds(JsonArray knowledge) {
        Set<String> ids = new HashSet<>();
        for (JsonElement element : knowledge) {
            ids.add(element.getAsJsonObject().get("chunkId").getAsString());
        }
        return ids;
    }

    public record RecommendationResult(
            String status,
            String answer,
            String queryText,
            List<String> userConstraints,
            List<RecommendationValidator.Recommendation> recommendations,
            List<String> followUpQuestions
    ) {
    }

    public static final class RecommendationUnavailableException extends RuntimeException {
        public RecommendationUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
