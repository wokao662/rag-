package com.example.rag.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 对对话画像 Agent 的决定做结构和安全边界校验。 */
public final class ProfileDecisionValidator {
    private static final Set<String> ALLOWED_ACTIONS = Set.of("ask", "recommend");
    private static final int MAX_QUESTION_LENGTH = 160;

    public record Decision(
            String action,
            boolean ready,
            double confidence,
            String reason,
            List<String> missingInformation,
            List<String> conflicts,
            String nextQuestion
    ) {
    }

    public Decision validate(JsonObject output) {
        String action = requiredString(output, "action");
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("action 只能是 ask 或 recommend");
        }

        boolean ready = requiredBoolean(output, "ready");
        if (ready != "recommend".equals(action)) {
            throw new IllegalArgumentException("ready 必须与 action 一致");
        }

        double confidence = requiredNumber(output, "confidence");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence 必须在 0 到 1 之间");
        }

        String reason = requiredString(output, "reason");
        List<String> missing = stringList(output, "missingInformation");
        List<String> conflicts = stringList(output, "conflicts");
        String nextQuestion = optionalString(output, "nextQuestion");

        if (ready && !nextQuestion.isBlank()) {
            throw new IllegalArgumentException("准备推荐时 nextQuestion 必须为空");
        }
        if (!ready && nextQuestion.isBlank()) {
            throw new IllegalArgumentException("继续画像时必须提供一个追问");
        }
        if (nextQuestion.length() > MAX_QUESTION_LENGTH) {
            throw new IllegalArgumentException("追问过长，请只询问一个重点");
        }
        if (nextQuestion.contains("\n")) {
            throw new IllegalArgumentException("追问必须是单个问题，不能换行列出问题清单");
        }

        return new Decision(action, ready, confidence, reason,
                List.copyOf(missing), List.copyOf(conflicts), nextQuestion);
    }

    private static String requiredString(JsonObject object, String name) {
        String value = optionalString(object, name);
        if (value.isBlank()) throw new IllegalArgumentException("缺少非空字段 " + name);
        return value;
    }

    private static String optionalString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) return "";
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " 必须是字符串或 null");
        }
        return value.getAsString().trim();
    }

    private static boolean requiredBoolean(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isBoolean()) {
            throw new IllegalArgumentException(name + " 必须是布尔值");
        }
        return value.getAsBoolean();
    }

    private static double requiredNumber(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " 必须是数字");
        }
        return value.getAsDouble();
    }

    private static List<String> stringList(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IllegalArgumentException(name + " 必须是字符串数组");
        }
        List<String> result = new ArrayList<>();
        JsonArray array = value.getAsJsonArray();
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                    || item.getAsString().isBlank()) {
                throw new IllegalArgumentException(name + " 只能包含非空字符串");
            }
            result.add(item.getAsString().trim());
        }
        return result;
    }
}
