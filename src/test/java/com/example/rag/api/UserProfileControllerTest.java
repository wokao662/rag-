package com.example.rag.api;

import com.example.rag.profile.UserProfileService;
import com.example.rag.recommendation.FeedbackService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    @MockitoBean
    private FeedbackService feedbackService;

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
    void listsConversations() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(profileService.listConversations("web-user-001"))
                .thenReturn(List.of(new UserProfileService.ConversationSummary(
                        conversationId, "我背单词很快忘", "active", "2026-09-09T10:00:00Z", "2026-09-09T11:00:00Z")));

        mockMvc.perform(get("/api/v1/users/web-user-001/conversations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conversationId").value(conversationId.toString()))
                .andExpect(jsonPath("$[0].title").value("我背单词很快忘"))
                .andExpect(jsonPath("$[0].status").value("active"));
    }

    @Test
    void returnsConversationMessages() throws Exception {
        UUID conversationId = UUID.randomUUID();
        when(profileService.getMessages("web-user-001", conversationId))
                .thenReturn(List.of(
                        new UserProfileService.MessageView(UUID.randomUUID(), "user",
                                "我背单词很快忘", java.util.Map.of(), "2026-09-09T10:00:00Z"),
                        new UserProfileService.MessageView(UUID.randomUUID(), "assistant",
                                "你每天大约有多少学习时间？", java.util.Map.of("messageType", "profile_question"),
                                "2026-09-09T10:00:05Z")));

        mockMvc.perform(get("/api/v1/users/web-user-001/conversations/{id}/messages", conversationId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].role").value("user"))
                .andExpect(jsonPath("$[1].metadata.messageType").value("profile_question"));
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
    void recordsFeedback() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(feedbackService.record("web-user-001", messageId, "strategy-keyword-mnemonic", "adopted"))
                .thenReturn(new FeedbackService.FeedbackResult(
                        messageId, "strategy-keyword-mnemonic", "adopted"));

        mockMvc.perform(post("/api/v1/users/web-user-001/messages/{id}/feedback", messageId)
                        .contentType("application/json")
                        .content("{\"strategyId\":\"strategy-keyword-mnemonic\",\"action\":\"adopted\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("strategy-keyword-mnemonic"))
                .andExpect(jsonPath("$.action").value("adopted"));
    }

    @Test
    void rejectsInvalidFeedbackAction() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/messages/{id}/feedback", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"strategyId\":\"strategy-keyword-mnemonic\",\"action\":\"love\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsInvalidExternalId() throws Exception {
        mockMvc.perform(post("/api/v1/users/{externalId}/conversations", "invalid user"))
                .andExpect(status().isBadRequest());
    }
}
