package com.example.rag.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * 将通过校验的最新明确表达合并进画像，并解析本轮属于哪个目标情境。
 *
 * <p>画像结构见 {@link EpisodeProfile}：共享层 + 情境层。合并时：
 * <ol>
 *   <li>先按 episodeDecision 定位目标情境（续现有 / 开新 / 兜底续当前活跃）；</li>
 *   <li>把每个字段按 {@link EpisodeProfile#SHARED_FIELDS} 路由到共享层，其余进目标情境；</li>
 *   <li>把目标情境设为当前活跃情境，并触发活跃数量上限归档。</li>
 * </ol>
 * episodeDecision 缺失或非法时保守兜底为"续当前活跃情境"，绝不让坏模型输出打断对话。
 */
public final class UserProfileMerger {
    public JsonObject merge(
            JsonObject existingProfile,
            JsonObject acceptedUpdates,
            JsonObject episodeDecision,
            UUID evidenceMessageId
    ) {
        JsonObject profile = EpisodeProfile.normalize(existingProfile);
        JsonObject shared = profile.getAsJsonObject("shared");
        JsonObject target = resolveTargetEpisode(profile, episodeDecision);

        String now = OffsetDateTime.now().toString();
        for (Map.Entry<String, JsonElement> entry : acceptedUpdates.entrySet()) {
            String field = entry.getKey();
            JsonObject fieldValue = entry.getValue().getAsJsonObject().deepCopy();
            fieldValue.addProperty("evidenceMessageId", evidenceMessageId.toString());
            fieldValue.addProperty("updatedAt", now);
            if (EpisodeProfile.SHARED_FIELDS.contains(field)) {
                shared.add(field, fieldValue);
            } else {
                // 情境字段（含未知字段保守归入当前情境，不丢信息）。
                target.add(field, fieldValue);
            }
        }

        target.addProperty("status", "active");
        target.addProperty("updatedAt", now);
        String targetId = target.get("id").getAsString();
        profile.addProperty("activeEpisodeId", targetId);
        EpisodeProfile.enforceActiveLimit(profile, targetId);
        return profile;
    }

    /** 按模型的情境决策定位目标情境对象；必要时新建并挂进 episodes 数组。 */
    private JsonObject resolveTargetEpisode(JsonObject profile, JsonObject episodeDecision) {
        JsonArray episodes = profile.getAsJsonArray("episodes");
        String action = decisionString(episodeDecision, "action");
        String episodeId = decisionString(episodeDecision, "episodeId");
        String label = decisionString(episodeDecision, "label");

        if ("continue".equals(action)) {
            JsonObject existing = EpisodeProfile.findEpisode(profile, episodeId);
            if (existing != null) {
                if (!label.isBlank()) existing.addProperty("label", EpisodeProfile.trimLabel(label));
                return existing;
            }
            // episodeId 无效：退回当前活跃情境，不新建。
            return EpisodeProfile.activeEpisode(profile);
        }

        if ("new".equals(action)) {
            // 防碎片：新 label 与某活跃情境同名时复用，不重复建。
            JsonObject sameLabel = EpisodeProfile.findActiveEpisodeByLabel(profile, label);
            if (sameLabel != null) return sameLabel;
            // 优先复用空的活跃情境（如新用户归一化遗留的空默认情境），避免留空壳。
            JsonObject empty = EpisodeProfile.findEmptyActiveEpisode(profile);
            if (empty != null) {
                empty.addProperty("label", EpisodeProfile.trimLabel(label));
                return empty;
            }
            JsonObject created = EpisodeProfile.newEpisode(EpisodeProfile.generateEpisodeId(), label);
            episodes.add(created);
            return created;
        }

        // action 缺失或非法：保守续当前活跃情境。
        return EpisodeProfile.activeEpisode(profile);
    }

    private static String decisionString(JsonObject decision, String key) {
        if (decision == null || !decision.has(key) || decision.get(key).isJsonNull()) return "";
        JsonElement element = decision.get(key);
        return element.isJsonPrimitive() ? element.getAsString().trim() : "";
    }
}
