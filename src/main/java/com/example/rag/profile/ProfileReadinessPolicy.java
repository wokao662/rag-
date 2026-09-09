package com.example.rag.profile;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 用确定性规则决定画像是否足够进行第一版学习策略推荐。 */
public final class ProfileReadinessPolicy {
    private static final List<String> COMPLETENESS_FIELDS = List.of(
            "learningGoal", "learningContent", "mainDifficulty",
            "availableMinutesPerDay", "daysUntilDeadline"
    );

    public record Decision(boolean ready, double completeness, List<String> missingFields, String followUpQuestion) {
    }

    public Decision evaluate(JsonObject profile) {
        boolean hasDifficulty = hasValue(profile, "mainDifficulty");
        boolean hasGoal = hasValue(profile, "learningGoal");
        boolean hasContent = hasValue(profile, "learningContent");
        boolean ready = hasDifficulty || (hasGoal && hasContent);

        List<String> missing = new ArrayList<>();
        COMPLETENESS_FIELDS.stream().filter(field -> !hasValue(profile, field)).forEach(missing::add);
        double completeness = (COMPLETENESS_FIELDS.size() - missing.size())
                / (double) COMPLETENESS_FIELDS.size();

        String question = null;
        if (!ready) {
            if (!hasDifficulty) {
                question = "你目前最想解决的学习困难是什么，例如容易遗忘、难以理解，还是无法坚持？";
            } else if (!hasContent) {
                question = "你现在主要在学习什么内容？";
            } else {
                question = "你希望通过这次学习达到什么目标？";
            }
        }
        return new Decision(ready, completeness, List.copyOf(missing), question);
    }

    private static boolean hasValue(JsonObject profile, String field) {
        return profile != null && profile.has(field) && profile.get(field).isJsonObject()
                && profile.getAsJsonObject(field).has("value")
                && !profile.getAsJsonObject(field).get("value").isJsonNull();
    }
}
