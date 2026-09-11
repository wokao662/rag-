package com.example.rag.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecommendationServiceTest {
    @Test
    void mergeAppendsExpandedChunksWithoutDuplicates() {
        JsonArray knowledge = new JsonArray();
        knowledge.add(knowledgeItem("chunk-1"));

        JsonArray expanded = new JsonArray();
        expanded.add(point("chunk-1", "strategy-a"));
        expanded.add(point("chunk-2", "strategy-a"));
        expanded.add(point("chunk-3", "strategy-b"));

        JsonArray merged = RecommendationService.mergeKnowledge(knowledge, expanded);

        assertEquals(3, merged.size());
        assertEquals("chunk-1", merged.get(0).getAsJsonObject().get("chunkId").getAsString());
        JsonObject appended = merged.get(1).getAsJsonObject();
        assertEquals("chunk-2", appended.get("chunkId").getAsString());
        assertEquals("steps", appended.get("chunkType").getAsString());
        assertFalse(appended.has("retrievalScore"));
    }

    @Test
    void mergeKeepsKnowledgeWhenNothingNew() {
        JsonArray knowledge = new JsonArray();
        knowledge.add(knowledgeItem("chunk-1"));

        JsonArray expanded = new JsonArray();
        expanded.add(point("chunk-1", "strategy-a"));

        JsonArray merged = RecommendationService.mergeKnowledge(knowledge, expanded);

        assertEquals(1, merged.size());
        assertTrue(merged.get(0).getAsJsonObject().has("retrievalScore"));
    }

    @Test
    void strategyIdsDeduplicateAndKeepRetrievalOrder() {
        JsonArray hits = new JsonArray();
        hits.add(point("chunk-1", "strategy-b"));
        hits.add(point("chunk-2", "strategy-a"));
        hits.add(point("chunk-3", "strategy-b"));

        // 去重后仍按召回顺序：补齐 chunk 时的查询顺序与相似度排序一致。
        assertEquals(List.of("strategy-b", "strategy-a"), RecommendationService.strategyIdsOf(hits));
        assertEquals(List.of(), RecommendationService.strategyIdsOf(new JsonArray()));
    }

    @Test
    void gateFilterDropsBlockedStrategiesOnly() {
        JsonArray hits = new JsonArray();
        hits.add(point("chunk-1", "strategy-a"));
        hits.add(point("chunk-2", "strategy-b"));
        hits.add(point("chunk-3", "strategy-a"));

        JsonArray admitted = RecommendationService.admitByGate(hits, Set.of("strategy-a"));

        // 同一策略的多个命中要么全留要么全走，闸门粒度是策略不是 chunk。
        assertEquals(2, admitted.size());
        assertEquals("chunk-1", admitted.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("chunk-3", admitted.get(1).getAsJsonObject().get("id").getAsString());
        assertEquals(0, RecommendationService.admitByGate(hits, Set.of()).size());
    }

    private static JsonObject knowledgeItem(String chunkId) {
        JsonObject item = new JsonObject();
        item.addProperty("chunkId", chunkId);
        item.addProperty("retrievalScore", 0.9);
        return item;
    }

    private static JsonObject point(String chunkId, String strategyId) {
        JsonObject payload = new JsonObject();
        payload.addProperty("strategyId", strategyId);
        payload.addProperty("strategyName", "策略名称");
        payload.addProperty("chunkType", "steps");
        payload.addProperty("text", "具体步骤");
        payload.add("sourceIds", new JsonArray());
        JsonObject point = new JsonObject();
        point.addProperty("id", chunkId);
        point.add("payload", payload);
        return point;
    }
}
