package com.example.rag.profile;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 新会话首条消息判定：决定抽取阶段是否对模型隐藏旧情境清单。 */
class UserProfileServiceTest {

    @Test
    void onlyUserMessageInSessionCountsAsFirst() {
        assertTrue(UserProfileService.isFirstUserMessage(List.of(message("user"))));
    }

    @Test
    void laterTurnsAreNotFirst() {
        assertFalse(UserProfileService.isFirstUserMessage(
                List.of(message("user"), message("assistant"), message("user"))));
    }

    @Test
    void emptyHistoryIsNotFirst() {
        assertFalse(UserProfileService.isFirstUserMessage(List.of()));
    }

    private static MessageRepository.StoredMessage message(String role) {
        return new MessageRepository.StoredMessage(
                UUID.randomUUID(), UUID.randomUUID(), role, "内容", new JsonObject(), OffsetDateTime.now());
    }
}
