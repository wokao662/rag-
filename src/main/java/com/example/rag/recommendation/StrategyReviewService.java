package com.example.rag.recommendation;

import com.example.rag.auth.AccessCodeService;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 人工审核：待审队列与审核决定。
 *
 * <p>这是把 V6 那四列（reviewed_by / reviewed_at / review_note / reviewer_score）从"建了没人写"
 * 变成有人写的地方。没有它，13 个策略永远停在 draft，{@code GOVERNANCE_REVIEW_GATE} 只能关着，
 * 闸门里"未审核不推荐"和"曝光人数上限"两条规则一直空转；reviewer_score 也永远是 0 条，
 * 而它与合成分的差值正是适合人群预测模型的核心训练标签。
 *
 * <p>权限模型：审核资格属于访问码（{@code access_codes.role}），不属于用户。同一个人可以同时
 * 持有测试码与审核码，审核码泄露时单独停用即可，不影响他继续使用产品。校验一律失败关闭——
 * 待审列表也不允许开发模式匿名读取，因为它暴露的是整个知识库的质量状况与审核进度。
 */
@Service
public class StrategyReviewService {
    /** 通过：策略进入渐进投放，从 seed 档开始曝光。 */
    public static final String DECISION_APPROVED = "approved";
    /** 驳回：策略不对外服务，但保留在待审队列里，投稿者改完可以重审。 */
    public static final String DECISION_REJECTED = "rejected";
    /** 审核备注上限。它会在审核界面里展示，不是日志，不给无限长。 */
    static final int MAX_NOTE_LENGTH = 2000;

    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final AccessCodeService accessCodes;
    private final StrategyGovernanceService governance;
    private final StrategyReviewRepository reviews = new StrategyReviewRepository();

    public StrategyReviewService(
            DataSource dataSource,
            TransactionTemplate transactions,
            AccessCodeService accessCodes,
            StrategyGovernanceService governance
    ) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.accessCodes = accessCodes;
        this.governance = governance;
    }

    /** 待审队列。只有 reviewer 码能读。 */
    public List<PendingStrategy> pending(String reviewerCode) {
        accessCodes.requireReviewer(reviewerCode);
        return inTransaction(connection -> reviews.pending(connection).stream()
                .map(StrategyReviewService::viewOf)
                .toList());
    }

    /**
     * 落一次审核决定。写审核四列与（审核者补的）两个输入分，然后重算该策略的派生分。
     *
     * <p>重算不是可选的收尾：审核者补了 evidence_score / effectiveness_score 之后，
     * overall_score 必须跟着变，否则库里就会多一份"写了但没人读、也没人更新"的分——
     * 这正是本项目已经踩过两次的坑（reviewStatus 只写不读、overall_score 算了没人用）。
     * 与决定本身同事务，所以不会出现"分补上了但合成分还是旧的"这种中间态。
     */
    public ReviewOutcome decide(String reviewerCode, String strategyId, Decision decision) {
        AccessCodeService.Reviewer reviewer = accessCodes.requireReviewer(reviewerCode);
        String target = requireStrategyId(strategyId);
        String verdict = decisionOf(decision.decision());
        Double reviewerScore = scoreOf("reviewerScore", decision.reviewerScore());
        Double evidenceScore = scoreOf("evidenceScore", decision.evidenceScore());
        Double effectivenessScore = scoreOf("effectivenessScore", decision.effectivenessScore());
        // 通过就必须给分：这条标签缺了就永久缺了，没有人会回头补。驳回不需要——
        // 驳回的策略不进入投放，也就不会成为模型二的训练样本。
        if (DECISION_APPROVED.equals(verdict) && reviewerScore == null) {
            throw new IllegalArgumentException("通过一个策略时必须给出 reviewerScore（0 到 1）");
        }
        String note = noteOf(decision.note());

        return inTransaction(connection -> {
            StrategyReviewRepository.ReviewTarget found = reviews.find(connection, target)
                    .orElseThrow(() -> new StrategyNotFoundException("策略不存在：" + target));
            OffsetDateTime reviewedAt = reviews.applyDecision(connection, found.strategyId(), verdict,
                    reviewer.label(), note, reviewerScore, evidenceScore, effectivenessScore);
            governance.recalculate(found.strategyId());
            return new ReviewOutcome(found.strategyId(), verdict, reviewer.label(),
                    reviewedAt == null ? null : reviewedAt.toString(), reviewerScore,
                    found.reviewStatus());
        });
    }

    /**
     * 审核决定的取值。大小写与首尾空白在这里收口，而不是散在控制器和前端：
     * 落库的是 {@code review_status}，它被 V6 的 CHECK 约束限死为五个值，
     * 写错一个字母就是一次数据库异常（对外表现为 503"数据库暂时不可用"），而不是 400。
     */
    static String decisionOf(String raw) {
        if (raw == null) throw new IllegalArgumentException("decision 不能为空");
        String normalized = raw.trim().toLowerCase();
        if (!DECISION_APPROVED.equals(normalized) && !DECISION_REJECTED.equals(normalized)) {
            throw new IllegalArgumentException("decision 只能是 approved 或 rejected，实际：" + raw);
        }
        return normalized;
    }

    /**
     * 分数取值校验。null 表示"这次不改"，是合法输入（审核者可以只给结论不补分）。
     *
     * <p>在应用层拦而不只靠 V10 的 CHECK：约束冲突会以 {@code DataAccessException} 冒出来，
     * 被全局处理器翻成 503"数据库暂时不可用"——把一个填错表单说成数据库坏了。
     */
    static Double scoreOf(String field, Double value) {
        if (value == null) return null;
        if (value < 0 || value > 1) {
            throw new IllegalArgumentException(field + " 必须在 0 到 1 之间，实际：" + value);
        }
        return value;
    }

    static String requireStrategyId(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("strategyId 不能为空");
        String trimmed = raw.trim();
        if (trimmed.length() > 128) {
            throw new IllegalArgumentException("strategyId 不能超过 128 个字符");
        }
        return trimmed;
    }

    /** 空备注存 NULL 而不是空串：两者在审核界面里都表示"没留话"，不该在库里长成两种样子。 */
    static String noteOf(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new IllegalArgumentException("note 不能超过 " + MAX_NOTE_LENGTH + " 个字符");
        }
        return trimmed;
    }

    private static PendingStrategy viewOf(StrategyReviewRepository.PendingRow row) {
        StrategyReviewRepository.PreviousReview previous = row.previousReview();
        return new PendingStrategy(
                row.strategyId(), row.name(), row.summary(), row.stepCount(), row.chunkCount(),
                row.reviewStatus(), row.archiveMissing(), row.pendingArchive(),
                row.evidenceScore(), row.effectivenessScore(), row.communityScore(), row.overallScore(),
                row.triedCount(), row.helpfulCount(),
                previous == null ? null : new PreviousReviewView(
                        previous.reviewedBy(),
                        previous.reviewedAt() == null ? null : previous.reviewedAt().toString(),
                        previous.reviewNote(), previous.reviewerScore()));
    }

    private <T> T inTransaction(SqlWork<T> work) {
        return transactions.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                return work.execute(connection);
            } catch (SQLException error) {
                throw new DataAccessResourceFailureException("数据库操作失败", error);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    /**
     * 一次审核决定的输入。
     *
     * @param decision           {@code approved} 或 {@code rejected}
     * @param note               审核备注，可空
     * @param reviewerScore      审核者独立打分；通过时必填，与合成分的差值是模型二的训练标签
     * @param evidenceScore      审核者补的文献证据分，null 表示不改档案导入的值
     * @param effectivenessScore 审核者补的有效性分，null 表示不改
     */
    public record Decision(
            String decision,
            String note,
            Double reviewerScore,
            Double evidenceScore,
            Double effectivenessScore
    ) {
    }

    /** 待审队列的一行。时间戳转成字符串，与 ConversationSummary 等既有响应保持一致。 */
    public record PendingStrategy(
            String strategyId,
            String name,
            String summary,
            int stepCount,
            long chunkCount,
            String reviewStatus,
            boolean archiveMissing,
            boolean pendingArchive,
            double evidenceScore,
            double effectivenessScore,
            double communityScore,
            double overallScore,
            int triedCount,
            int helpfulCount,
            PreviousReviewView previousReview
    ) {
    }

    public record PreviousReviewView(
            String reviewedBy,
            String reviewedAt,
            String reviewNote,
            Double reviewerScore
    ) {
    }

    /**
     * 审核结果。
     *
     * @param previousStatus 决定之前的状态，让调用方看得出这是一次首审还是重审
     */
    public record ReviewOutcome(
            String strategyId,
            String reviewStatus,
            String reviewedBy,
            String reviewedAt,
            Double reviewerScore,
            String previousStatus
    ) {
    }

    public static final class StrategyNotFoundException extends RuntimeException {
        public StrategyNotFoundException(String message) { super(message); }
    }
}
