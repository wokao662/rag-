package com.example.rag.api;

import com.example.rag.recommendation.StrategyReviewService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 人工审核端点。
 *
 * <p><b>为什么挂在 {@code /api/v1/users/{externalId}} 下面，而不是新开一个 {@code /api/v1/reviews}</b>：
 * {@link com.example.rag.auth.AccessCodeFilter} 的正则只匹配 {@code ^/api/v1/users/([^/]+)(?:/.*)?$}，
 * 不匹配就直接放行——一个新前缀会得到一个公网匿名可读写的接口，除非再为它单独写一套鉴权。
 * 挂在既有前缀下就自动继承那道拦截（码必须等于路径里的 externalId），服务层再补一次角色校验即可。
 * 顺带一个实际好处：前端现成的 {@code apiUrl(path)} 拼接方式不用改，审核界面直接能用。
 *
 * <p>身份取自路径里的 {@code externalId} 而不是请求头，因为在本项目里访问码本身就是 externalId
 * （{@code redeem} 返回的就是规范化后的码），现有全部端点也都是这个约定。两条路径都失败关闭：
 * 发过码时过滤器保证 externalId 是一张有效码；一张码都没发时（本地开发模式）过滤器放行，
 * 但 {@link com.example.rag.auth.AccessCodeService#requireReviewer} 查不到这张码，照样 401。
 * 审核是特权动作，不参与开发模式放行。
 */
@Validated
@RestController
@RequestMapping("/api/v1/users/{externalId}/reviews")
public class ReviewController {
    private final StrategyReviewService reviewService;

    public ReviewController(StrategyReviewService reviewService) {
        this.reviewService = reviewService;
    }

    /**
     * 待审队列：所有还不是 approved / archived 的策略，从没人看过的排最前，
     * 再按合成分从高到低。带上 stepCount 与 chunkCount，让"通过之后会发生什么"在点下去之前就看得见：
     * stepCount 为 0 会生成一张没有步骤的推荐卡，chunkCount 为 0 则根本召不回来。
     */
    @GetMapping("/pending")
    public List<StrategyReviewService.PendingStrategy> pending(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return reviewService.pending(externalId);
    }

    /**
     * 落一次审核决定：写 reviewed_by / reviewed_at / review_note / reviewer_score 四列，
     * 通过时把 review_status 改成 approved，并在同一事务里重算派生分。
     */
    @PostMapping("/{strategyId}/decision")
    public StrategyReviewService.ReviewOutcome decide(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable @NotBlank @Size(max = 128) String strategyId,
            @Valid @RequestBody DecisionRequest request
    ) {
        return reviewService.decide(externalId, strategyId, new StrategyReviewService.Decision(
                request.decision(), request.note(), request.reviewerScore(),
                request.evidenceScore(), request.effectivenessScore()));
    }

    /**
     * @param decision           大小写不敏感，服务层统一收口成小写再落库
     * @param reviewerScore      通过时必填（服务层校验）；它是模型二的训练标签，缺一条就少一条
     * @param evidenceScore      审核者补分。导入器对缺分的档案会警告"按 0 导入，需要审核者补分"，
     *                           这里就是补分的地方；传 null 表示不改
     * @param effectivenessScore 同上
     */
    public record DecisionRequest(
            @NotBlank(message = "decision 不能为空")
            @Pattern(regexp = "(?i)approved|rejected", message = "decision 只能是 approved 或 rejected")
            String decision,

            @Size(max = 2000, message = "note 不能超过 2000 个字符")
            String note,

            @DecimalMin(value = "0.0", message = "reviewerScore 必须在 0 到 1 之间")
            @DecimalMax(value = "1.0", message = "reviewerScore 必须在 0 到 1 之间")
            Double reviewerScore,

            @DecimalMin(value = "0.0", message = "evidenceScore 必须在 0 到 1 之间")
            @DecimalMax(value = "1.0", message = "evidenceScore 必须在 0 到 1 之间")
            Double evidenceScore,

            @DecimalMin(value = "0.0", message = "effectivenessScore 必须在 0 到 1 之间")
            @DecimalMax(value = "1.0", message = "effectivenessScore 必须在 0 到 1 之间")
            Double effectivenessScore
    ) {
    }
}
