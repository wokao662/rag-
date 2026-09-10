package com.example.rag.api;

import com.example.rag.auth.AccessCodeService;
import com.example.rag.profile.UserProfileService;
import com.example.rag.recommendation.FeedbackService;
import com.example.rag.recommendation.RecommendationHistoryService;
import com.example.rag.recommendation.RecommendationService;
import com.example.rag.recommendation.RecommendationValidator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
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

    @MockitoBean
    private RecommendationHistoryService historyService;

    @MockitoBean
    private AccessCodeService accessCodes;

    @org.junit.jupiter.api.BeforeEach
    void allowAllAccessCodes() {
        when(accessCodes.isAllowed(any(), any())).thenReturn(true);
    }

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
                                "我背单词很快忘", java.util.Map.of(), "2026-09-09T10:00:00Z", List.of()),
                        new UserProfileService.MessageView(UUID.randomUUID(), "assistant",
                                "你每天大约有多少学习时间？", java.util.Map.of("messageType", "profile_question"),
                                "2026-09-09T10:00:05Z", List.of())));

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
        when(feedbackService.record("web-user-001", messageId, "strategy-keyword-mnemonic", "liked"))
                .thenReturn(new FeedbackService.FeedbackResult(
                        messageId, "strategy-keyword-mnemonic", "liked"));

        mockMvc.perform(post("/api/v1/users/web-user-001/messages/{id}/feedback", messageId)
                        .contentType("application/json")
                        .content("{\"strategyId\":\"strategy-keyword-mnemonic\",\"action\":\"liked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.strategyId").value("strategy-keyword-mnemonic"))
                .andExpect(jsonPath("$.action").value("liked"));
    }

    @Test
    void rejectsInvalidFeedbackAction() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/messages/{id}/feedback", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"strategyId\":\"strategy-keyword-mnemonic\",\"action\":\"love\"}"))
                .andExpect(status().isBadRequest());
    }

    /** adopted / dismissed 已随「卡片上只做点赞」的决定退役，旧客户端不能继续写入。 */
    @Test
    void rejectsRetiredFeedbackAction() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/messages/{id}/feedback", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"strategyId\":\"strategy-keyword-mnemonic\",\"action\":\"adopted\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsRecommendationHistory() throws Exception {
        UUID messageId = UUID.randomUUID();
        when(historyService.history("web-user-001")).thenReturn(List.of(
                new RecommendationHistoryService.RecommendationEvent(
                        messageId, UUID.randomUUID(), "2026-09-10T10:00:00Z", "answer", "建议使用练习测试。",
                        List.of(new RecommendationHistoryService.RecommendedMethod(
                                "strategy-practice-testing", "练习测试", "适合看过书但记不住的情况",
                                List.of("合上书本回想主要内容"), List.of("source-006"), List.of(),
                                true,
                                new RecommendationHistoryService.TrialState(
                                        true, "helpful", "确实记住了", "2026-09-10T12:00:00Z"))))));

        mockMvc.perform(get("/api/v1/users/web-user-001/recommendation-history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].messageId").value(messageId.toString()))
                .andExpect(jsonPath("$[0].methods[0].strategyName").value("练习测试"))
                .andExpect(jsonPath("$[0].methods[0].liked").value(true))
                .andExpect(jsonPath("$[0].methods[0].trial.outcome").value("helpful"));
    }

    @Test
    void recordsTrialFeedback() throws Exception {
        when(historyService.submitTrialFeedback(any(), any(), any())).thenReturn(
                new RecommendationHistoryService.TrialState(
                        true, "helpful", "确实记住了", "2026-09-10T12:00:00Z"));

        mockMvc.perform(post("/api/v1/users/web-user-001/strategies/{id}/trial-feedback",
                        "strategy-practice-testing")
                        .contentType("application/json")
                        .content("{\"tried\":true,\"outcome\":\"helpful\",\"note\":\"确实记住了\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tried").value(true))
                .andExpect(jsonPath("$.outcome").value("helpful"));
    }

    /** 防刷校验：不能对系统没推荐过的方法提交反馈，否则会凭空污染升降权统计。 */
    @Test
    void rejectsTrialFeedbackForStrategyNeverRecommended() throws Exception {
        when(historyService.submitTrialFeedback(any(), any(), any()))
                .thenThrow(new RecommendationHistoryService.StrategyNotRecommendedException(
                        "这个方法没有推荐给你过，无法提交反馈：strategy-fake"));

        mockMvc.perform(post("/api/v1/users/web-user-001/strategies/{id}/trial-feedback", "strategy-fake")
                        .contentType("application/json")
                        .content("{\"tried\":true,\"outcome\":\"helpful\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("资源不存在"));
    }

    @Test
    void rejectsUnknownTrialOutcome() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/strategies/{id}/trial-feedback",
                        "strategy-practice-testing")
                        .contentType("application/json")
                        .content("{\"tried\":true,\"outcome\":\"amazing\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsTrialFeedbackWithoutTried() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/strategies/{id}/trial-feedback",
                        "strategy-practice-testing")
                        .contentType("application/json")
                        .content("{\"outcome\":\"helpful\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsRequestWhenCodeNotAllowed() throws Exception {
        when(accessCodes.isAllowed(any(), any())).thenReturn(false);

        mockMvc.perform(post("/api/v1/users/web-user-001/conversations"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsInvalidExternalId() throws Exception {
        mockMvc.perform(post("/api/v1/users/{externalId}/conversations", "invalid user"))
                .andExpect(status().isBadRequest());
    }
}
