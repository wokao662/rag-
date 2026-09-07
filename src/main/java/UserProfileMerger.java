import com.google.gson.JsonObject;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** 将通过校验的最新明确表达合并进已有画像。 */
public final class UserProfileMerger {
    public JsonObject merge(JsonObject existingProfile, JsonObject acceptedUpdates, UUID evidenceMessageId) {
        JsonObject merged = existingProfile == null ? new JsonObject() : existingProfile.deepCopy();
        for (Map.Entry<String, com.google.gson.JsonElement> entry : acceptedUpdates.entrySet()) {
            JsonObject field = entry.getValue().getAsJsonObject().deepCopy();
            field.addProperty("evidenceMessageId", evidenceMessageId.toString());
            field.addProperty("updatedAt", OffsetDateTime.now().toString());
            merged.add(entry.getKey(), field);
        }
        return merged;
    }
}
