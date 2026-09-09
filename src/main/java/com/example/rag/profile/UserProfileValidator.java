package com.example.rag.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.Set;

/** 对模型画像候选做类型、范围、来源证据和字段白名单校验。 */
public final class UserProfileValidator {
    private enum ValueType { STRING, INTEGER, STRING_ARRAY }

    private static final Map<String, ValueType> FIELD_TYPES = Map.of(
            "learningGoal", ValueType.STRING,
            "learningContent", ValueType.STRING,
            "mainDifficulty", ValueType.STRING,
            "availableMinutesPerDay", ValueType.INTEGER,
            "daysUntilDeadline", ValueType.INTEGER,
            "preferredLearningStyle", ValueType.STRING,
            "triedMethods", ValueType.STRING_ARRAY
    );
    private static final Set<String> REQUIRED_PROPERTIES =
            Set.of("value", "sourceType", "confidence", "evidence");

    public record ValidationResult(JsonObject acceptedUpdates, JsonArray rejections) {
    }

    public ValidationResult validate(JsonObject extraction, String currentMessage) {
        JsonObject accepted = new JsonObject();
        JsonArray rejections = new JsonArray();
        JsonObject updates = extraction.getAsJsonObject("updates");

        for (Map.Entry<String, JsonElement> entry : updates.entrySet()) {
            String field = entry.getKey();
            try {
                JsonObject candidate = requireObject(entry.getValue(), field);
                validateCandidate(field, candidate, currentMessage);
                accepted.add(field, candidate.deepCopy());
            } catch (IllegalArgumentException error) {
                JsonObject rejection = new JsonObject();
                rejection.addProperty("field", field);
                rejection.addProperty("reason", error.getMessage());
                rejections.add(rejection);
            }
        }
        return new ValidationResult(accepted, rejections);
    }

    private static JsonObject requireObject(JsonElement element, String field) {
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(field + "必须是对象");
        }
        return element.getAsJsonObject();
    }

    private static void validateCandidate(String field, JsonObject candidate, String currentMessage) {
        ValueType type = FIELD_TYPES.get(field);
        if (type == null) throw new IllegalArgumentException("字段不在允许列表");
        for (String property : REQUIRED_PROPERTIES) {
            if (!candidate.has(property) || candidate.get(property).isJsonNull()) {
                throw new IllegalArgumentException("缺少" + property);
            }
        }
        if (!"explicit".equals(candidate.get("sourceType").getAsString())) {
            throw new IllegalArgumentException("当前只接受explicit信息");
        }
        double confidence = candidate.get("confidence").getAsDouble();
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new IllegalArgumentException("confidence必须在0～1之间");
        }
        String evidence = candidate.get("evidence").getAsString().trim();
        if (evidence.isEmpty() || !normalize(currentMessage).contains(normalize(evidence))) {
            throw new IllegalArgumentException("evidence不是当前用户消息的原文");
        }

        JsonElement value = candidate.get("value");
        switch (type) {
            case STRING -> {
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                        || value.getAsString().isBlank()) {
                    throw new IllegalArgumentException("value必须是非空字符串");
                }
            }
            case INTEGER -> validateInteger(field, value);
            case STRING_ARRAY -> {
                if (!value.isJsonArray()) throw new IllegalArgumentException("value必须是字符串数组");
                for (JsonElement item : value.getAsJsonArray()) {
                    if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                            || item.getAsString().isBlank()) {
                        throw new IllegalArgumentException("value必须是非空字符串数组");
                    }
                }
            }
        }
    }

    private static void validateInteger(String field, JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException("value必须是整数");
        }
        double number = value.getAsDouble();
        if (!Double.isFinite(number) || number != Math.rint(number)) {
            throw new IllegalArgumentException("value必须是整数");
        }
        int integer = value.getAsInt();
        if ("availableMinutesPerDay".equals(field) && (integer < 1 || integer > 1440)) {
            throw new IllegalArgumentException("每天可用分钟数必须在1～1440之间");
        }
        if ("daysUntilDeadline".equals(field) && (integer < 0 || integer > 36500)) {
            throw new IllegalArgumentException("截止日期天数必须在0～36500之间");
        }
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").trim();
    }
}
