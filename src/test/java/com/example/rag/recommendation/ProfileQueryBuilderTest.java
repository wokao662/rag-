package com.example.rag.recommendation;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileQueryBuilderTest {
    private static final Gson GSON = new Gson();

    private final ProfileQueryBuilder queryBuilder = new ProfileQueryBuilder();

    @Test
    void buildsOrderedQueryWithUnits() {
        JsonObject profile = new JsonObject();
        profile.add("learningGoal", field("通过英语四级考试"));
        profile.add("learningContent", field("英语单词"));
        profile.add("mainDifficulty", field("背完很快忘记"));
        profile.add("availableMinutesPerDay", field(30));
        profile.add("daysUntilDeadline", field(60));
        profile.add("triedMethods", field(List.of("死记硬背", "只看不复习")));

        String query = queryBuilder.build(profile);

        assertEquals("学习内容：英语单词；学习目标：通过英语四级考试；主要困难：背完很快忘记；"
                + "每天可用学习时间：30分钟；距离截止日期：60天；已尝试过的方法：死记硬背、只看不复习", query);
    }

    @Test
    void skipsMissingFields() {
        JsonObject profile = new JsonObject();
        profile.add("mainDifficulty", field("看完书就忘"));

        assertEquals("主要困难：看完书就忘", queryBuilder.build(profile));
    }

    @Test
    void prependsCurrentMessageAsRetrievalSignal() {
        JsonObject profile = new JsonObject();
        profile.add("learningContent", field("CET-4 单词"));
        profile.add("mainDifficulty", field("背单词很快忘"));

        // 当前消息在最前：它是最强的意图信号，检索结果必须随用户问法变化。
        assertEquals("当前问题：除了记单词还有什么方法；学习内容：CET-4 单词；主要困难：背单词很快忘",
                queryBuilder.build(profile, "除了记单词还有什么方法"));
    }

    @Test
    void blankOrNullCurrentMessageFallsBackToProfileOnly() {
        JsonObject profile = new JsonObject();
        profile.add("mainDifficulty", field("看完书就忘"));

        assertEquals("主要困难：看完书就忘", queryBuilder.build(profile, null));
        assertEquals("主要困难：看完书就忘", queryBuilder.build(profile, "   "));
    }

    @Test
    void currentMessageIsSingleLineAndTruncated() {
        JsonObject profile = new JsonObject();
        profile.add("mainDifficulty", field("看完书就忘"));

        String query = queryBuilder.build(profile, "第一行\n第二行 " + "长".repeat(600));

        // 换行压成单行；消息截断到 500 字符，避免稀释画像约束。
        assertFalse(query.contains("\n"));
        assertEquals("当前问题：".length() + 500 + "；主要困难：看完书就忘".length(), query.length());
        assertTrue(query.startsWith("当前问题：第一行 第二行 长"));
    }

    @Test
    void rejectsEmptyProfile() {
        assertThrows(IllegalArgumentException.class, () -> queryBuilder.build(new JsonObject()));
    }

    private static JsonObject field(Object value) {
        JsonObject field = new JsonObject();
        field.add("value", GSON.toJsonTree(value));
        field.addProperty("sourceType", "explicit");
        return field;
    }
}
