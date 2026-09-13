package com.example.rag.profile;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 情境化画像核心逻辑：向后兼容归一化、扁平视图、按情境路由合并、数量上限归档。 */
class EpisodeProfileTest {
    private static final Gson GSON = new Gson();
    private final UserProfileMerger merger = new UserProfileMerger();

    @Test
    void normalizeWrapsLegacyFlatProfileIntoSharedAndDefaultEpisode() {
        JsonObject legacy = new JsonObject();
        legacy.add("learningGoal", field("通过四级"));
        legacy.add("preferredLearningStyle", field("看视频"));
        legacy.add("availableMinutesPerDay", field(30));

        JsonObject normalized = EpisodeProfile.normalize(legacy);

        assertTrue(EpisodeProfile.isEpisodeShaped(normalized));
        assertEquals("ep_default", normalized.get("activeEpisodeId").getAsString());
        JsonObject shared = normalized.getAsJsonObject("shared");
        assertTrue(shared.has("preferredLearningStyle"));
        assertTrue(shared.has("availableMinutesPerDay"));
        JsonObject episode = normalized.getAsJsonArray("episodes").get(0).getAsJsonObject();
        assertTrue(episode.has("learningGoal"));
        assertFalse(episode.has("preferredLearningStyle"), "共享字段不该落进情境");
    }

    @Test
    void flattenedActiveViewRoundTripsLegacyFields() {
        JsonObject legacy = new JsonObject();
        legacy.add("learningGoal", field("通过四级"));
        legacy.add("mainDifficulty", field("记不住"));
        legacy.add("availableMinutesPerDay", field(30));

        JsonObject flat = EpisodeProfile.flattenedActiveView(EpisodeProfile.normalize(legacy));

        assertTrue(flat.has("learningGoal"));
        assertTrue(flat.has("mainDifficulty"));
        assertTrue(flat.has("availableMinutesPerDay"));
        assertFalse(flat.has("episodes"), "扁平视图不能带结构键，否则下游会当成字段");
    }

    @Test
    void mergeWithNewDecisionAdoptsEmptyEpisodeAndRoutesSharedFields() {
        JsonObject existing = EpisodeProfile.normalize(new JsonObject());
        JsonObject updates = new JsonObject();
        updates.add("learningGoal", field("练听力"));
        updates.add("preferredLearningStyle", field("听音频"));
        JsonObject decision = decision("new", "", "四级听力");

        JsonObject merged = merger.merge(existing, updates, decision, UUID.randomUUID());

        JsonObject shared = merged.getAsJsonObject("shared");
        assertTrue(shared.has("preferredLearningStyle"));
        assertFalse(shared.has("learningGoal"));
        JsonObject active = EpisodeProfile.activeEpisode(merged);
        assertEquals("四级听力", active.get("label").getAsString());
        assertTrue(active.has("learningGoal"));
        assertEquals(1, merged.getAsJsonArray("episodes").size(), "应复用空默认情境，不新建空壳");
    }

    @Test
    void mergeWithContinueDecisionWritesIntoSameEpisode() {
        JsonObject seeded = merger.merge(
                EpisodeProfile.normalize(new JsonObject()),
                updatesOf("learningGoal", "考好数学"), decision("new", "", "数学期末"), UUID.randomUUID());
        String episodeId = EpisodeProfile.activeEpisodeId(seeded);

        JsonObject merged = merger.merge(
                seeded, updatesOf("mainDifficulty", "函数题不会"),
                decision("continue", episodeId, ""), UUID.randomUUID());

        assertEquals(1, merged.getAsJsonArray("episodes").size());
        assertEquals(episodeId, EpisodeProfile.activeEpisodeId(merged));
        assertTrue(EpisodeProfile.activeEpisode(merged).has("mainDifficulty"));
    }

    @Test
    void secondNewDecisionCreatesSecondEpisodeAndKeepsFirst() {
        JsonObject math = merger.merge(
                EpisodeProfile.normalize(new JsonObject()),
                updatesOf("learningGoal", "考好数学"), decision("new", "", "数学期末"), UUID.randomUUID());

        JsonObject both = merger.merge(
                math, updatesOf("learningGoal", "过四级听力"), decision("new", "", "四级听力"), UUID.randomUUID());

        assertEquals(2, both.getAsJsonArray("episodes").size());
        assertEquals("四级听力", EpisodeProfile.activeEpisode(both).get("label").getAsString());
        // 数学情境仍在，且没有被英语覆盖
        JsonObject mathEpisode = EpisodeProfile.findActiveEpisodeByLabel(both, "数学期末");
        assertTrue(mathEpisode != null && mathEpisode.has("learningGoal"));
        assertEquals("考好数学", mathEpisode.getAsJsonObject("learningGoal").get("value").getAsString());
    }

    @Test
    void mergeWithMissingDecisionFallsBackToActiveEpisode() {
        JsonObject merged = merger.merge(
                EpisodeProfile.normalize(new JsonObject()), updatesOf("learningGoal", "背单词"), null, UUID.randomUUID());

        assertEquals("ep_default", EpisodeProfile.activeEpisodeId(merged));
        assertTrue(EpisodeProfile.activeEpisode(merged).has("learningGoal"));
    }

    @Test
    void enforceActiveLimitArchivesOldestButKeepsCurrent() {
        JsonObject profile = EpisodeProfile.normalize(new JsonObject());
        for (int i = 0; i < 6; i++) {
            JsonObject episode = EpisodeProfile.newEpisode("ep_" + i, "情境" + i);
            episode.addProperty("updatedAt", OffsetDateTime.now().plusSeconds(i + 1).toString());
            profile.getAsJsonArray("episodes").add(episode);
        }

        EpisodeProfile.enforceActiveLimit(profile, "ep_5");

        assertEquals(EpisodeProfile.MAX_ACTIVE_EPISODES, countActive(profile));
        assertEquals("active", statusOf(profile, "ep_5"), "本轮用到的情境绝不能被归档");
    }

    private static int countActive(JsonObject profile) {
        int count = 0;
        for (JsonElement element : profile.getAsJsonArray("episodes")) {
            JsonObject episode = element.getAsJsonObject();
            if (!episode.has("status") || "active".equals(episode.get("status").getAsString())) count++;
        }
        return count;
    }

    private static String statusOf(JsonObject profile, String id) {
        for (JsonElement element : profile.getAsJsonArray("episodes")) {
            JsonObject episode = element.getAsJsonObject();
            if (id.equals(episode.get("id").getAsString())) {
                return episode.has("status") ? episode.get("status").getAsString() : "active";
            }
        }
        return null;
    }

    private static JsonObject decision(String action, String episodeId, String label) {
        return JsonParser.parseString(
                "{\"action\":\"" + action + "\",\"episodeId\":\"" + episodeId + "\",\"label\":\"" + label + "\"}")
                .getAsJsonObject();
    }

    private static JsonObject updatesOf(String fieldName, Object value) {
        JsonObject updates = new JsonObject();
        updates.add(fieldName, field(value));
        return updates;
    }

    private static JsonObject field(Object value) {
        JsonObject field = new JsonObject();
        field.add("value", GSON.toJsonTree(value));
        field.addProperty("sourceType", "explicit");
        field.addProperty("confidence", 1.0);
        field.addProperty("evidence", "x");
        return field;
    }
}
