package com.example.rag.api;

import com.example.rag.profile.UserProfileService;
import com.example.rag.recommendation.FeedbackService;
import com.example.rag.recommendation.RecommendationHistoryService;
import com.example.rag.recommendation.RecommendationService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Validated
@RestController
@RequestMapping("/api/v1/users/{externalId}")
public class UserProfileController {
    /** SSE 流最多挂 120 秒；Cloudflare 免费隧道的 100 秒硬上限会先到，这里只留安全余量。 */
    private static final long STREAM_TIMEOUT_MS = 120_000L;

    private final UserProfileService profileService;
    private final FeedbackService feedbackService;
    private final RecommendationHistoryService historyService;

    public UserProfileController(
            UserProfileService profileService,
            FeedbackService feedbackService,
            RecommendationHistoryService historyService
    ) {
        this.profileService = profileService;
        this.feedbackService = feedbackService;
        this.historyService = historyService;
    }

    /**
     * 流式处理在独立线程池上跑：SSE 端点必须立刻返回 emitter，模型管线（最长约 30 秒）放到
     * 后台线程逐事件推送。8 个线程够测试规模并发；daemon 线程不阻塞进程退出。
     */
    private final ExecutorService streamExecutor = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "turn-stream");
        thread.setDaemon(true);
        return thread;
    });

    @GetMapping("/conversations")
    public List<UserProfileService.ConversationSummary> listConversations(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return profileService.listConversations(externalId);
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileService.ConversationStarted startConversation(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return profileService.startConversation(externalId);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public UserProfileService.TurnResult sendMessage(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return profileService.processMessage(externalId, conversationId, request.content());
    }

    /**
     * 发消息的流式版本：同一轮处理以 SSE 推送进度与增量文本（事件协议见 api.TurnStreamSink）。
     * 前端拿 final 事件里的 TurnResult 做最终渲染，与非流式接口完全同构。
     */
    @PostMapping(value = "/conversations/{conversationId}/messages/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter sendMessageStreaming(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        TurnStreamSink sink = new TurnStreamSink(emitter);
        streamExecutor.execute(() -> {
            try {
                profileService.processMessageStreaming(externalId, conversationId, request.content(), sink);
            } catch (Exception error) {
                sink.error(streamErrorMessage(error));
            } finally {
                sink.complete();
            }
        });
        return emitter;
    }

    /** 与非流式接口的 ApiExceptionHandler 保持同一套用户可读文案。 */
    private static String streamErrorMessage(Exception error) {
        if (error instanceof DataAccessException) return "数据库暂时不可用，请稍后重试";
        if (error instanceof UserProfileService.ProfileModelException
                || error instanceof UserProfileService.ProfileNotFoundException
                || error instanceof UserProfileService.ConversationNotFoundException
                || error instanceof UserProfileService.ProfileConflictException
                || error instanceof IllegalArgumentException) {
            String message = error.getMessage();
            if (message != null && !message.isBlank()) return message;
        }
        return "服务暂时不可用，请稍后再试";
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public List<UserProfileService.MessageView> getMessages(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable UUID conversationId
    ) {
        return profileService.getMessages(externalId, conversationId);
    }

    /** 卡片上的点赞，只用于向投稿者展示鼓励数，不参与推荐度计算。 */
    @PostMapping("/messages/{messageId}/feedback")
    public FeedbackService.FeedbackResult sendFeedback(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable UUID messageId,
            @Valid @RequestBody SendFeedbackRequest request
    ) {
        return feedbackService.record(externalId, messageId, request.strategyId(), request.action());
    }

    /**
     * 历史推荐列表，按时间倒序。每条带上当时的推荐快照以及点赞、尝试后反馈的当前状态。
     * 「按方法聚合」视图由前端分组完成，后端只提供一份数据。
     *
     * <p>不叫 GET /recommendations：那个路径的 POST 是「基于当前画像立即生成一次推荐」，
     * 返回的是单个 RecommendationResult；而这里读的是历史上随消息存下来的快照列表，
     * 两者不是同一资源的创建与集合，共用路径会造成误用。
     */
    @GetMapping("/recommendation-history")
    public List<RecommendationHistoryService.RecommendationEvent> getRecommendationHistory(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return historyService.history(externalId);
    }

    /** 尝试后反馈，是驱动渐进投放升降权的唯一信号。重复提交视为修改。 */
    @PostMapping("/strategies/{strategyId}/trial-feedback")
    public RecommendationHistoryService.TrialState submitTrialFeedback(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String strategyId,
            @Valid @RequestBody TrialFeedbackRequest request
    ) {
        return historyService.submitTrialFeedback(
                externalId,
                strategyId,
                new RecommendationHistoryService.TrialSubmission(
                        request.tried(), request.outcome(), request.note(), request.sourceMessageId()));
    }

    @PostMapping("/recommendations")
    public RecommendationService.RecommendationResult recommend(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return profileService.recommend(externalId);
    }

    @GetMapping("/profile")
    public UserProfileService.ProfileResult getProfile(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return profileService.getProfile(externalId);
    }

    public record SendFeedbackRequest(
            @NotBlank(message = "strategyId 不能为空")
            @Size(max = 128, message = "strategyId 不能超过 128 个字符")
            String strategyId,
            @NotBlank(message = "action 不能为空")
            @Pattern(regexp = "liked", message = "action 只能是 liked")
            String action
    ) {
    }

    /**
     * tried 与 outcome 必须自洽，数据库层有同样的复合 CHECK 约束。
     * not_suitable 表示方法本身没问题但不适合这个用户，不参与降权。
     */
    public record TrialFeedbackRequest(
            @NotNull(message = "tried 不能为空")
            Boolean tried,
            @NotBlank(message = "outcome 不能为空")
            @Pattern(regexp = "helpful|partial|not_helpful|not_suitable|no_time",
                    message = "outcome 只能是 helpful、partial、not_helpful、not_suitable 或 no_time")
            String outcome,
            @Size(max = 2000, message = "note 不能超过 2000 个字符")
            String note,
            UUID sourceMessageId
    ) {
    }

    public record SendMessageRequest(
            @NotBlank(message = "content 不能为空")
            @Size(max = 10_000, message = "content 不能超过 10000 个字符")
            String content
    ) {
    }

    /** 应用关闭时停掉流式线程池；已在跑的一轮会随线程中断结束（此时连接本已随关闭断开）。 */
    @PreDestroy
    void shutdownStreamExecutor() {
        streamExecutor.shutdownNow();
    }
}
