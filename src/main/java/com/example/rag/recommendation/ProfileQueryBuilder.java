package com.example.rag.recommendation;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/** 把结构化用户画像与当前问题整理成用于 Qdrant 检索的自然语言查询。 */
public final class ProfileQueryBuilder {
    /** 当前消息进入检索文本前的截断上限：避免用户粘贴长文时稀释画像约束的向量语义。 */
    private static final int CURRENT_MESSAGE_CHARS = 500;

    /**
     * 按对推荐结果的影响程度拼接画像字段；数值约束保留单位，避免检索时丢失语义。
     */
    public String build(JsonObject profile) {
        return build(profile, null);
    }

    /**
     * 带当前问题的版本：用户这一轮的原话是最强的意图信号（“除了背单词还有什么方法”与纯画像
     * 查询的向量语义完全不同），必须进入检索文本。此前检索只用画像拼接，用户问什么没有区别，
     * 同一画像下每轮召回的都是同一批 chunk。当前消息为空白时退回纯画像查询（独立推荐入口）。
     */
    public String build(JsonObject profile, String currentUserMessage) {
        if (profile == null || profile.size() == 0) {
            throw new IllegalArgumentException("画像为空，无法生成检索条件");
        }

        List<String> parts = new ArrayList<>();
        addCurrentMessage(parts, currentUserMessage);
        addPart(parts, profile, "learningContent", "学习内容：", "");
        addPart(parts, profile, "learningGoal", "学习目标：", "");
        addPart(parts, profile, "mainDifficulty", "主要困难：", "");
        addPart(parts, profile, "availableMinutesPerDay", "每天可用学习时间：", "分钟");
        addPart(parts, profile, "daysUntilDeadline", "距离截止日期：", "天");
        addPart(parts, profile, "preferredLearningStyle", "偏好学习方式：", "");
        addPart(parts, profile, "triedMethods", "已尝试过的方法：", "");

        if (parts.isEmpty()) {
            throw new IllegalArgumentException("画像中没有可用于检索的信息");
        }
        return String.join("；", parts);
    }

    /** 当前消息单行化并截断后作为首个检索片段；空白消息不产生片段。 */
    private static void addCurrentMessage(List<String> parts, String currentUserMessage) {
        if (currentUserMessage == null) return;
        String text = currentUserMessage.trim();
        if (text.isEmpty()) return;
        text = text.replaceAll("\\s+", " ");
        if (text.length() > CURRENT_MESSAGE_CHARS) {
            text = text.substring(0, CURRENT_MESSAGE_CHARS);
        }
        parts.add("当前问题：" + text);
    }

    private static void addPart(List<String> parts, JsonObject profile, String field, String label, String unit) {
        JsonElement element = profile.get(field);
        if (element == null || !element.isJsonObject()) return;
        String value = valueAsString(element.getAsJsonObject().get("value"));
        if (value.isBlank()) return;
        parts.add(label + value + unit);
    }

    private static String valueAsString(JsonElement value) {
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonArray()) {
            JsonArray array = value.getAsJsonArray();
            List<String> items = new ArrayList<>();
            for (JsonElement item : array) {
                String text = valueAsString(item);
                if (!text.isBlank()) items.add(text);
            }
            return String.join("、", items);
        }
        if (value.isJsonPrimitive()) return value.getAsString().trim();
        return "";
    }
}
