package com.example.rag.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 情境化画像（episode）的结构工具。
 *
 * <p>画像形态从"一人一份扁平字段"升级为：共享层(shared) + 若干目标情境(episodes) + activeEpisodeId。
 * 本类集中处理新旧结构归一化、当前情境解析、给下游的扁平视图、情境摘要与数量上限，
 * 让 readiness / queryBuilder / decider / recommend 这些下游消费者无需感知结构变化——
 * 它们继续吃 {@link #flattenedActiveView} 产出的扁平画像。
 *
 * <p>所有方法都防御式处理畸形输入：一次坏数据或坏模型输出不能打断对话。
 */
public final class EpisodeProfile {
    private EpisodeProfile() {
    }

    /** 跨情境稳定的字段：换个学习目标也带得走（人的偏好与作息）。 */
    public static final Set<String> SHARED_FIELDS = Set.of("preferredLearningStyle", "availableMinutesPerDay");

    /** 随目标情境变化的字段：数学的困难和英语的困难不是一回事。 */
    public static final Set<String> EPISODE_FIELDS =
            Set.of("learningGoal", "learningContent", "mainDifficulty", "daysUntilDeadline", "triedMethods");

    /** 活跃情境数量上限，超了自动归档最旧的，防止碎片化。 */
    public static final int MAX_ACTIVE_EPISODES = 5;

    public static final String DEFAULT_EPISODE_ID = "ep_default";
    public static final String DEFAULT_EPISODE_LABEL = "当前学习";
    private static final int MAX_LABEL_LENGTH = 24;

    private static final String KEY_SHARED = "shared";
    private static final String KEY_EPISODES = "episodes";
    private static final String KEY_ACTIVE = "activeEpisodeId";
    private static final String KEY_ID = "id";
    private static final String KEY_LABEL = "label";
    private static final String KEY_STATUS = "status";
    private static final String KEY_UPDATED_AT = "updatedAt";
    private static final String STATUS_ACTIVE = "active";
    private static final String STATUS_ARCHIVED = "archived";

    /** 是否已经是新结构（带 episodes 数组）。 */
    public static boolean isEpisodeShaped(JsonObject profile) {
        return profile != null && profile.has(KEY_EPISODES) && profile.get(KEY_EPISODES).isJsonArray();
    }

    /**
     * 把任意形态的画像归一成 {@code {shared, episodes, activeEpisodeId}}。
     *
     * <p>旧的扁平结构（直接是 learningGoal 等字段）会被包成一个默认情境，共享字段抽到 shared；
     * 已经是新结构的做完整性补齐后返回深拷贝；null/空返回带一个空默认情境的骨架。
     * 这是向后兼容的关键：库里 9 份老画像读进来时经此归一，一份都不会丢。
     */
    public static JsonObject normalize(JsonObject raw) {
        if (isEpisodeShaped(raw)) {
            JsonObject copy = raw.deepCopy();
            if (!copy.has(KEY_SHARED) || !copy.get(KEY_SHARED).isJsonObject()) {
                copy.add(KEY_SHARED, new JsonObject());
            }
            JsonArray episodes = copy.getAsJsonArray(KEY_EPISODES);
            if (episodes.size() == 0) {
                episodes.add(newEpisode(DEFAULT_EPISODE_ID, DEFAULT_EPISODE_LABEL));
            }
            String active = copy.has(KEY_ACTIVE) && copy.get(KEY_ACTIVE).isJsonPrimitive()
                    ? copy.get(KEY_ACTIVE).getAsString() : null;
            if (active == null || findEpisode(copy, active) == null) {
                copy.addProperty(KEY_ACTIVE, episodes.get(0).getAsJsonObject().get(KEY_ID).getAsString());
            }
            return copy;
        }

        JsonObject shared = new JsonObject();
        JsonObject episode = newEpisode(DEFAULT_EPISODE_ID, DEFAULT_EPISODE_LABEL);
        if (raw != null) {
            for (Map.Entry<String, JsonElement> entry : raw.entrySet()) {
                String field = entry.getKey();
                JsonElement value = entry.getValue();
                if (SHARED_FIELDS.contains(field)) {
                    shared.add(field, value.deepCopy());
                } else if (EPISODE_FIELDS.contains(field)) {
                    episode.add(field, value.deepCopy());
                }
                // 其余未知键保守忽略，不带进新结构。
            }
        }
        JsonArray episodes = new JsonArray();
        episodes.add(episode);

        JsonObject result = new JsonObject();
        result.add(KEY_SHARED, shared);
        result.add(KEY_EPISODES, episodes);
        result.addProperty(KEY_ACTIVE, DEFAULT_EPISODE_ID);
        return result;
    }

    public static JsonObject newEpisode(String id, String label) {
        JsonObject episode = new JsonObject();
        episode.addProperty(KEY_ID, id);
        episode.addProperty(KEY_LABEL, trimLabel(label));
        episode.addProperty(KEY_STATUS, STATUS_ACTIVE);
        episode.addProperty(KEY_UPDATED_AT, OffsetDateTime.now().toString());
        return episode;
    }

    public static String generateEpisodeId() {
        return "ep_" + UUID.randomUUID().toString().substring(0, 8);
    }

    public static String trimLabel(String label) {
        if (label == null) return DEFAULT_EPISODE_LABEL;
        String trimmed = label.trim().replaceAll("\\s+", " ");
        if (trimmed.isEmpty()) return DEFAULT_EPISODE_LABEL;
        return trimmed.length() <= MAX_LABEL_LENGTH ? trimmed : trimmed.substring(0, MAX_LABEL_LENGTH);
    }

    public static JsonObject findEpisode(JsonObject profile, String episodeId) {
        if (profile == null || episodeId == null || !isEpisodeShaped(profile)) return null;
        for (JsonElement element : profile.getAsJsonArray(KEY_EPISODES)) {
            JsonObject episode = element.getAsJsonObject();
            if (episode.has(KEY_ID) && episode.get(KEY_ID).isJsonPrimitive()
                    && episodeId.equals(episode.get(KEY_ID).getAsString())) {
                return episode;
            }
        }
        return null;
    }

    /** 按 label 找活跃情境，用于"开新"时防止把同一目标重复建成多个情境。 */
    public static JsonObject findActiveEpisodeByLabel(JsonObject profile, String label) {
        if (profile == null || label == null || label.isBlank() || !isEpisodeShaped(profile)) return null;
        String target = trimLabel(label);
        for (JsonElement element : profile.getAsJsonArray(KEY_EPISODES)) {
            JsonObject episode = element.getAsJsonObject();
            if (!STATUS_ACTIVE.equals(statusOf(episode))) continue;
            if (episode.has(KEY_LABEL) && episode.get(KEY_LABEL).isJsonPrimitive()
                    && target.equals(trimLabel(episode.get(KEY_LABEL).getAsString()))) {
                return episode;
            }
        }
        return null;
    }

    /**
     * 找一个还没有任何情境字段的活跃情境（例如归一化遗留的空默认情境）。
     * "开新"时优先复用它并重新命名，避免留下空壳情境造成碎片。
     */
    public static JsonObject findEmptyActiveEpisode(JsonObject profile) {
        if (!isEpisodeShaped(profile)) return null;
        for (JsonElement element : profile.getAsJsonArray(KEY_EPISODES)) {
            JsonObject episode = element.getAsJsonObject();
            if (!STATUS_ACTIVE.equals(statusOf(episode))) continue;
            boolean hasContent = false;
            for (String field : EPISODE_FIELDS) {
                if (episode.has(field)) {
                    hasContent = true;
                    break;
                }
            }
            if (!hasContent) return episode;
        }
        return null;
    }

    public static String activeEpisodeId(JsonObject profile) {
        JsonObject normalized = isEpisodeShaped(profile) ? profile : normalize(profile);
        return normalized.get(KEY_ACTIVE).getAsString();
    }

    /** 当前活跃情境对象；解析不到时退回第一个情境，绝不返回 null。 */
    public static JsonObject activeEpisode(JsonObject profile) {
        JsonObject normalized = isEpisodeShaped(profile) ? profile : normalize(profile);
        String activeId = normalized.has(KEY_ACTIVE) && normalized.get(KEY_ACTIVE).isJsonPrimitive()
                ? normalized.get(KEY_ACTIVE).getAsString() : null;
        JsonObject episode = findEpisode(normalized, activeId);
        if (episode != null) return episode;
        return normalized.getAsJsonArray(KEY_EPISODES).get(0).getAsJsonObject();
    }

    /**
     * 给下游消费者的扁平视图：共享层字段 + 当前情境字段合并成一个扁平对象，形状与旧画像一致。
     * 共享字段集与情境字段集不相交，不会互相覆盖。
     */
    public static JsonObject flattenedActiveView(JsonObject profile) {
        JsonObject normalized = isEpisodeShaped(profile) ? profile : normalize(profile);
        JsonObject flat = new JsonObject();
        JsonObject shared = normalized.getAsJsonObject(KEY_SHARED);
        if (shared != null) {
            for (Map.Entry<String, JsonElement> entry : shared.entrySet()) {
                flat.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        JsonObject episode = activeEpisode(normalized);
        for (Map.Entry<String, JsonElement> entry : episode.entrySet()) {
            if (EPISODE_FIELDS.contains(entry.getKey())) {
                flat.add(entry.getKey(), entry.getValue().deepCopy());
            }
        }
        return flat;
    }

    /**
     * 新会话首条消息的抽取向导：清空情境清单（保留共享层）。
     *
     * <p>抽取器拿到它后按「清单为空时一律开新」的既有规则为新会话开新情境——
     * “新会话=新话题”的边界由服务端保证，而不是靠模型从单条消息里猜。
     * 合并仍基于真实画像进行：延续旧话题时 new 出的同名情境会被 {@link UserProfileMerger} 复用回去。
     */
    public static JsonObject withoutActiveEpisodes(JsonObject profile) {
        JsonObject copy = normalize(profile);
        copy.add(KEY_EPISODES, new JsonArray());
        return copy;
    }

    /**
     * 给 extractor 的活跃情境摘要清单：id + label + 一句目标/内容，供模型判断"续哪个还是开新"。
     * 只列活跃情境，归档的不参与归属判断。
     */
    public static JsonArray episodeSummaries(JsonObject profile) {
        JsonArray summaries = new JsonArray();
        if (!isEpisodeShaped(profile)) return summaries;
        for (JsonElement element : profile.getAsJsonArray(KEY_EPISODES)) {
            JsonObject episode = element.getAsJsonObject();
            if (!STATUS_ACTIVE.equals(statusOf(episode))) continue;
            JsonObject summary = new JsonObject();
            summary.addProperty(KEY_ID, episode.has(KEY_ID) ? episode.get(KEY_ID).getAsString() : "");
            summary.addProperty(KEY_LABEL, episode.has(KEY_LABEL) ? episode.get(KEY_LABEL).getAsString() : "");
            summary.addProperty("goal", valueText(episode, "learningGoal"));
            summary.addProperty("content", valueText(episode, "learningContent"));
            summaries.add(summary);
        }
        return summaries;
    }

    /**
     * 活跃情境数量超过上限时，按 updatedAt 从旧到新归档，但绝不归档本轮刚用到的情境。
     * 这是防碎片化的第三道阀门。
     */
    public static void enforceActiveLimit(JsonObject profile, String keepEpisodeId) {
        if (!isEpisodeShaped(profile)) return;
        List<JsonObject> active = new ArrayList<>();
        for (JsonElement element : profile.getAsJsonArray(KEY_EPISODES)) {
            JsonObject episode = element.getAsJsonObject();
            if (STATUS_ACTIVE.equals(statusOf(episode))) active.add(episode);
        }
        if (active.size() <= MAX_ACTIVE_EPISODES) return;
        active.sort(Comparator.comparing(EpisodeProfile::updatedAtOf));
        int toArchive = active.size() - MAX_ACTIVE_EPISODES;
        for (JsonObject episode : active) {
            if (toArchive <= 0) break;
            String id = episode.has(KEY_ID) && episode.get(KEY_ID).isJsonPrimitive()
                    ? episode.get(KEY_ID).getAsString() : null;
            if (id != null && id.equals(keepEpisodeId)) continue;
            episode.addProperty(KEY_STATUS, STATUS_ARCHIVED);
            toArchive--;
        }
    }

    private static String statusOf(JsonObject episode) {
        return episode.has(KEY_STATUS) && episode.get(KEY_STATUS).isJsonPrimitive()
                ? episode.get(KEY_STATUS).getAsString() : STATUS_ACTIVE;
    }

    private static OffsetDateTime updatedAtOf(JsonObject episode) {
        try {
            if (episode.has(KEY_UPDATED_AT) && episode.get(KEY_UPDATED_AT).isJsonPrimitive()) {
                return OffsetDateTime.parse(episode.get(KEY_UPDATED_AT).getAsString());
            }
        } catch (RuntimeException ignored) {
            // 时间戳坏了就当作最旧，排序时优先被归档。
        }
        return OffsetDateTime.MIN;
    }

    /** 取某情境下一个画像字段的 value 文本（数组用顿号连接），供摘要展示。 */
    private static String valueText(JsonObject container, String field) {
        if (container == null || !container.has(field) || !container.get(field).isJsonObject()) return "";
        JsonObject wrapper = container.getAsJsonObject(field);
        if (!wrapper.has("value") || wrapper.get("value").isJsonNull()) return "";
        JsonElement value = wrapper.get("value");
        if (value.isJsonArray()) {
            List<String> items = new ArrayList<>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (item.isJsonPrimitive()) items.add(item.getAsString());
            }
            return String.join("、", items);
        }
        return value.isJsonPrimitive() ? value.getAsString() : "";
    }
}
