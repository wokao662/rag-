package com.example.rag.api;

import com.example.rag.auth.AccessCodeService;
import com.example.rag.recommendation.StrategyReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审核端点的接口测试。
 *
 * <p>这里重点测三件在别处测不到的事：审核资格被拒时的状态码分层（401 与 403 不是一回事）、
 * 落库的是审核者人名而不是访问码、以及请求体到 {@link StrategyReviewService.Decision} 的映射没有漏字段。
 */
@WebMvcTest({ReviewController.class, ApiExceptionHandler.class})
class ReviewControllerTest {
    private static final String REVIEWER = "REVIEWER-01";
    private static final String REVIEWER_NAME = "张审核";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StrategyReviewService reviewService;

    /**
     * {@code @WebMvcTest} 会把 {@code OncePerRequestFilter} 一起装载，所以 AccessCodeFilter 真的会跑。
     * 默认先让它全部放行：本类测的是控制器与服务层的行为，拦截器自己的行为在
     * {@link com.example.rag.auth.AccessCodeFilterTest} 里测。两层分开测，失败时才能看得出是哪一层坏了。
     */
    @MockitoBean
    private AccessCodeService accessCodes;

    @BeforeEach
    void allowAllAccessCodes() {
        when(accessCodes.isAllowed(any(), any())).thenReturn(true);
    }

    private static StrategyReviewService.PendingStrategy pendingRow() {
        return new StrategyReviewService.PendingStrategy(
                "strategy-spaced-learning", "间隔学习", "把复习分散到多个时间点",
                6, 12L, "draft", false, false,
                0.0, 0.0, 0.0, 0.0, 0, 0, null);
    }

    // ---------- 待审列表 ----------

    @Test
    void returnsPendingQueueToReviewer() throws Exception {
        when(reviewService.pending(REVIEWER)).thenReturn(List.of(pendingRow()));

        mockMvc.perform(get("/api/v1/users/{externalId}/reviews/pending", REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].strategyId").value("strategy-spaced-learning"))
                .andExpect(jsonPath("$[0].name").value("间隔学习"))
                // stepCount 与 chunkCount 必须在列表里：前者为 0 意味着通过后会生成一张
                // 没有步骤的推荐卡，后者为 0 意味着即使通过也召不回来。审核者要在点下去之前看见。
                .andExpect(jsonPath("$[0].stepCount").value(6))
                .andExpect(jsonPath("$[0].chunkCount").value(12))
                .andExpect(jsonPath("$[0].reviewStatus").value("draft"));
    }

    @Test
    void omitsPreviousReviewWhenNeverReviewed() throws Exception {
        // "从没人看过"与"看过了但没留话"必须在响应里能区分。靠 non_null 把整个对象省掉，
        // 前端判 previousReview 是否存在即可；若返回四个 null 字段的空对象，两者就长得一样了。
        when(reviewService.pending(REVIEWER)).thenReturn(List.of(pendingRow()));

        mockMvc.perform(get("/api/v1/users/{externalId}/reviews/pending", REVIEWER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].previousReview").doesNotExist());
    }

    @Test
    void forbidsTesterCodeOnPendingList() throws Exception {
        // 待审列表也不给 tester 看：它暴露的是整个知识库的质量状况与审核进度，
        // 不是"自己的用户空间"里的东西。403 而不是 401——码是有效的，只是没资格。
        when(reviewService.pending("TESTER-01"))
                .thenThrow(new AccessCodeService.ForbiddenException("该访问码没有审核资格"));

        mockMvc.perform(get("/api/v1/users/{externalId}/reviews/pending", "TESTER-01"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("无权限"))
                .andExpect(jsonPath("$.detail").value("该访问码没有审核资格"));
    }

    @Test
    void rejectsUnknownCodeOnPendingList() throws Exception {
        // 本地开发模式（access_codes 表为空）下过滤器会放行任何路径，但审核端点不参与那种放行：
        // 查不到这张码就是 401，失败关闭。
        when(reviewService.pending("NOT-ISSUED"))
                .thenThrow(new AccessCodeService.UnauthorizedException("访问码无效或已被停用"));

        mockMvc.perform(get("/api/v1/users/{externalId}/reviews/pending", "NOT-ISSUED"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("未授权"));
    }

    // ---------- 审核决定 ----------

    @Test
    void recordsReviewerNameAndNeverTheAccessCode() throws Exception {
        // 这是 reviewed_by 那条决定的验收点：落库和回显的都是 access_codes.label（人名），
        // 访问码是凭据，不能因为一次审核就扩散到第二张表和接口响应里。
        when(reviewService.decide(eq(REVIEWER), eq("strategy-spaced-learning"), any()))
                .thenReturn(new StrategyReviewService.ReviewOutcome(
                        "strategy-spaced-learning", "approved", REVIEWER_NAME,
                        "2026-09-11T10:00:00Z", 0.8, "draft"));

        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("{\"decision\":\"approved\",\"note\":\"证据充分\",\"reviewerScore\":0.8}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("approved"))
                .andExpect(jsonPath("$.reviewedBy").value(REVIEWER_NAME))
                .andExpect(jsonPath("$.previousStatus").value("draft"))
                .andExpect(content().string(not(containsString(REVIEWER))));
    }

    @Test
    void passesAllDecisionFieldsToTheService() throws Exception {
        // 三个分数各有用途，漏映射任何一个都不会报错，只会静默地"审核者补了分但没生效"。
        when(reviewService.decide(eq(REVIEWER), eq("strategy-spaced-learning"), any()))
                .thenReturn(new StrategyReviewService.ReviewOutcome(
                        "strategy-spaced-learning", "approved", REVIEWER_NAME,
                        "2026-09-11T10:00:00Z", 0.8, "draft"));

        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("""
                                {"decision":"approved","note":"档案缺两个分，按文献补上",
                                 "reviewerScore":0.8,"evidenceScore":0.7,"effectivenessScore":0.6}
                                """))
                .andExpect(status().isOk());

        ArgumentCaptor<StrategyReviewService.Decision> captor =
                ArgumentCaptor.forClass(StrategyReviewService.Decision.class);
        verify(reviewService).decide(eq(REVIEWER), eq("strategy-spaced-learning"), captor.capture());
        StrategyReviewService.Decision decision = captor.getValue();
        assertEquals("approved", decision.decision());
        assertEquals("档案缺两个分，按文献补上", decision.note());
        assertEquals(0.8, decision.reviewerScore());
        assertEquals(0.7, decision.evidenceScore());
        assertEquals(0.6, decision.effectivenessScore());
    }

    @Test
    void rejectsUnknownDecisionValue() throws Exception {
        // archived 是合法的 review_status 但不是审核端点能做的决定：归档须由人确认
        // pending_archive 标记、走单独的动作。放开它等于让这个端点变成"任意改审核状态"。
        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("{\"decision\":\"archived\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("decision 只能是 approved 或 rejected"));
    }

    @Test
    void rejectsScoreOutOfRange() throws Exception {
        // 必须在应用层拦成 400：只靠 V10 的 CHECK 约束，冲突会以 DataAccessException 冒出来，
        // 被全局处理器翻成 503"数据库暂时不可用"——把一个填错的表单说成数据库坏了。
        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("{\"decision\":\"approved\",\"reviewerScore\":7.5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("reviewerScore 必须在 0 到 1 之间"));
    }

    @Test
    void requiresReviewerScoreWhenApproving() throws Exception {
        // 通过却不打分，这条训练标签就永久缺了——没有人会回头补。驳回则不需要：
        // 被驳回的策略不进入投放，也就不会成为模型二的样本。
        when(reviewService.decide(eq(REVIEWER), eq("strategy-spaced-learning"), any()))
                .thenThrow(new IllegalArgumentException("通过一个策略时必须给出 reviewerScore（0 到 1）"));

        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("{\"decision\":\"approved\",\"note\":\"可以\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("通过一个策略时必须给出 reviewerScore（0 到 1）"));
    }

    @Test
    void allowsRejectionWithoutScore() throws Exception {
        when(reviewService.decide(eq(REVIEWER), eq("strategy-spaced-learning"), any()))
                .thenReturn(new StrategyReviewService.ReviewOutcome(
                        "strategy-spaced-learning", "rejected", REVIEWER_NAME,
                        "2026-09-11T10:00:00Z", null, "draft"));

        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("{\"decision\":\"rejected\",\"note\":\"步骤为空，先补方法\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewStatus").value("rejected"))
                .andExpect(jsonPath("$.reviewerScore").doesNotExist());
    }

    @Test
    void returnsNotFoundForUnknownStrategy() throws Exception {
        when(reviewService.decide(eq(REVIEWER), eq("strategy-typo"), any()))
                .thenThrow(new StrategyReviewService.StrategyNotFoundException("策略不存在：strategy-typo"));

        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        REVIEWER, "strategy-typo")
                        .contentType("application/json")
                        .content("{\"decision\":\"approved\",\"reviewerScore\":0.8}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("资源不存在"));
    }

    @Test
    void forbidsTesterCodeOnDecision() throws Exception {
        when(reviewService.decide(eq("TESTER-01"), eq("strategy-spaced-learning"), any()))
                .thenThrow(new AccessCodeService.ForbiddenException("该访问码没有审核资格"));

        mockMvc.perform(post("/api/v1/users/{externalId}/reviews/{strategyId}/decision",
                        "TESTER-01", "strategy-spaced-learning")
                        .contentType("application/json")
                        .content("{\"decision\":\"approved\",\"reviewerScore\":0.8}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.title").value("无权限"));
    }

    @Test
    void filterRejectsForeignCodeBeforeTheControllerRuns() throws Exception {
        // 两层防线的分工：过滤器先管“你是不是你”（码必须等于路径里的 externalId），
        // 服务层再管“你有没有资格”（role 必须是 reviewer）。这一条验证第一层确实生效：
        // 拦下来时控制器根本不该被调用，否则就是白拦。
        when(accessCodes.isAllowed(any(), any())).thenReturn(false);

        mockMvc.perform(get("/api/v1/users/{externalId}/reviews/pending", "SOMEONE-ELSE"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("需要有效的访问码"));

        verify(reviewService, never()).pending(any());
    }
}
