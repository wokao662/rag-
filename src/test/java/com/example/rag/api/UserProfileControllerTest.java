package com.example.rag.api;

import com.example.rag.profile.UserProfileService;
import com.example.rag.recommendation.RecommendationService;
import com.example.rag.recommendation.RecommendationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({UserProfileController.class, ApiExceptionHandler.class})
class UserProfileControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService profileService;

    @Test
    void createsConversation() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(profileService.startConversation("web-user-001"))
                .thenReturn(new UserProfileService.ConversationStarted(userId, conversationId));

        mockMvc.perform(post("/api/v1/users/web-user-001/conversations"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/conversations/{id}/messages", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数不合法"));
    }

    @Test
    void returnsRecommendation() throws Exception {
        RecommendationService.RecommendationResult result = new RecommendationService.RecommendationResult(
                "answer",
                "建议使用分散练习。",
                "学习内容：英语单词；每天可用学习时间：30分钟",
                List.of("每天30分钟"),
                List.of(new RecommendationValidator.Recommendation(
                        "strategy-distributed-practice", "分散练习", "适合记单词很快忘的情况",
                        List.of("把复习分散到多天"), List.of("source-001"),
                        List.of("chunk-1"), List.of())),
                List.of());
        when(profileService.recommend("web-user-001")).thenReturn(result);

        mockMvc.perform(post("/api/v1/users/web-user-001/recommendations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("answer"))
                .andExpect(jsonPath("$.recommendations[0].strategyName").value("分散练习"))
                .andExpect(jsonPath("$.recommendations[0].citations[0]").value("chunk-1"));
    }

    @Test
    void recommendationRequiresExistingProfile() throws Exception {
        when(profileService.recommend("web-user-001"))
                .thenThrow(new UserProfileService.ProfileNotFoundException("该用户还没有画像"));

        mockMvc.perform(post("/api/v1/users/web-user-001/recommendations"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("资源不存在"));
    }

    @Test
    void rejectsInvalidExternalId() throws Exception {
        mockMvc.perform(post("/api/v1/users/{externalId}/conversations", "invalid user"))
                .andExpect(status().isBadRequest());
    }
}
