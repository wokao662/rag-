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

    @Test
    void evidenceSourcesKeepOnlyRecommendedStrategiesAndParseFields() {
        JsonArray knowledge = new JsonArray();
        JsonObject evidence = evidenceItem("chunk-e1", "strategy-a", "策略A",
                "策略A的研究证据：分散练习有稳健收益。（出处：Cepeda et al. (2006) https://doi.org/1）");
        evidence.addProperty("evidenceUrl", "https://doi.org/1");
        knowledge.add(evidence);
        knowledge.add(evidenceItem("chunk-e2", "strategy-b", "策略B",
                "策略B的研究证据：与本次推荐无关。（出处：https://doi.org/2）"));
        knowledge.add(knowledgeEntry("steps", "chunk-1", "strategy-a", "策略A", "具体步骤"));

        List<RecommendationService.EvidenceSource> sources = RecommendationService.evidenceSources(
                knowledge, List.of(recommendation("strategy-a")));

        assertEquals(1, sources.size());
        assertEquals("strategy-a", sources.get(0).strategyId());
        assertEquals("分散练习有稳健收益。", sources.get(0).claim());
        // citation 不得把 URL 一起带上；URL 一律取 payload 的 evidenceUrl。
        assertEquals("Cepeda et al. (2006)", sources.get(0).citation());
        assertEquals("https://doi.org/1", sources.get(0).url());
    }

    @Test
    void evidenceSourcesParseUrlFromTextAndSkipMalformed() {
        JsonArray knowledge = new JsonArray();
        knowledge.add(evidenceItem("chunk-e1", "strategy-a", "策略A",
                "策略A的研究证据：只有结论与链接。（出处：作者 (2020) https://doi.org/9）"));
        knowledge.add(evidenceItem("chunk-e2", "strategy-a", "策略A",
                "策略A的研究证据：没有出处标记的尾段。"));
        knowledge.add(evidenceItem("chunk-e3", "strategy-a", "策略A", "前缀不符的文本（出处：x）"));

        List<RecommendationService.EvidenceSource> sources = RecommendationService.evidenceSources(
                knowledge, List.of(recommendation("strategy-a")));

        // 无 evidenceUrl 时从出处段尾部反解 URL；无出处标记时整段作为结论；前缀不符的跳过。
        assertEquals(2, sources.size());
        assertEquals("作者 (2020)", sources.get(0).citation());
        assertEquals("https://doi.org/9", sources.get(0).url());
        assertEquals("没有出处标记的尾段。", sources.get(1).claim());
        assertEquals("", sources.get(1).citation());
    }

    private static JsonObject knowledgeEntry(String chunkType, String chunkId, String strategyId,
                                             String strategyName, String text) {
        JsonObject item = new JsonObject();
        item.addProperty("chunkId", chunkId);
        item.addProperty("strategyId", strategyId);
        item.addProperty("strategyName", strategyName);
        item.addProperty("chunkType", chunkType);
        item.addProperty("content", text);
        return item;
    }

    private static JsonObject evidenceItem(String chunkId, String strategyId, String strategyName, String text) {
        return knowledgeEntry("evidence", chunkId, strategyId, strategyName, text);
    }

    private static RecommendationValidator.Recommendation recommendation(String strategyId) {
        return new RecommendationValidator.Recommendation(
                strategyId, "名称", "理由", List.of(), List.of(), List.of(), List.of());
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
