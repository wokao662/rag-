package com.example.rag.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

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
