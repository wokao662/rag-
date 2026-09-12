package com.example.rag.recommendation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 人工审核的持久化：待审队列与审核决定落库。
 *
 * <p>{@code strategies} 从 V6 建表起就有 reviewed_by / reviewed_at / review_note / reviewer_score
 * 四列，但到这里为止没有任何代码写入过它们——13 个策略全停在 draft，
 * {@code GOVERNANCE_REVIEW_GATE} 只能关着（开了会把推荐过滤成全空），reviewer_score 一条也没攒到。
 * 这个仓库补的就是那条写入路径。
 *
 * <p>与 {@link StrategyGovernanceRepository} 分工：那边写反馈消费出来的派生分
 * （community_score / overall_score / tried_count / helpful_count / exposure_state），
 * 这边只写人的判断。两边的列不重叠，所以审核不会抹掉真实用户数据，反馈重算也不会覆盖审核结论。
 * 唯一的交集是 evidence_score / effectiveness_score：V6 注明它们由"导入器与审核者"写，
 * 导入器只在策略仍为 draft 时跟随档案，一旦人工审核过档案就不再是权威来源。
 */
public final class StrategyReviewRepository {

    /**
     * 一次已存在的审核。从未审核过时整个字段为 null，而不是四个 null 组成的空对象：
     * 响应里 {@code default-property-inclusion: non_null} 会把它整个省掉，前端据此区分
     * "还没人看过"与"看过了但没留话"。
     */
    public record PreviousReview(
            String reviewedBy,
            OffsetDateTime reviewedAt,
            String reviewNote,
            Double reviewerScore
    ) {
    }

    /**
     * 待审队列的一行。
     *
     * @param stepCount  档案里的步骤数。为 0 的策略被推中时会生成一张没有步骤的推荐卡，
     *                   审核者必须在通过前看见这件事
     * @param chunkCount 检索库里的片段数。为 0 意味着即使通过也召不回来，审了也白审
     * @param triedCount / helpfulCount 与 communityScore 一起给：社区分在样本不足门槛时恒为 0，
     *                   光看 0 分区分不出"没人试过"与"试过但都说没用"
     */
    public record PendingRow(
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
            PreviousReview previousReview
    ) {
    }

    /** 审核目标。存在性检查与"它现在是什么状态"一次查回。 */
    public record ReviewTarget(String strategyId, String reviewStatus) {
    }

    /**
     * 待审队列：所有还不是 approved / archived 的策略。
     *
     * <p>不按 review_status = 'draft' 过滤，而是排除已定案的两态：被驳回过的策略同样需要
     * 出现在队列里（投稿者改完要能重审），而它上一次被驳回的理由就在 previousReview 里。
     *
     * <p>排序把"从没人看过"排在最前，再按合成分从高到低。合成分高的先审完就能先开闸放量，
     * 而垫底那几个（缺 evidenceScore / effectivenessScore 按 0 导入的）本来就要等审核者补分。
     *
     * <p>{@code jsonb_typeof} 守卫不是多余的：{@code jsonb_array_length} 遇到非数组会直接抛错，
     * 而那会让整个待审列表 500——一份格式写坏的档案就能让审核界面完全打不开。
     */
    public List<PendingRow> pending(Connection connection) throws SQLException {
        String sql = """
                SELECT s.strategy_id, s.name, s.summary,
                       CASE WHEN jsonb_typeof(s.steps) = 'array'
                            THEN jsonb_array_length(s.steps) ELSE 0 END AS step_count,
                       (SELECT COUNT(*) FROM strategy_chunks c
                         WHERE c.strategy_id = s.strategy_id) AS chunk_count,
                       s.review_status, s.archive_missing, s.pending_archive,
                       s.evidence_score, s.effectiveness_score,
                       s.community_score, s.overall_score,
                       s.tried_count, s.helpful_count,
                       s.reviewed_by, s.reviewed_at, s.review_note, s.reviewer_score
                FROM strategies s
                WHERE s.review_status NOT IN ('approved', 'archived')
                ORDER BY (s.reviewed_at IS NULL) DESC, s.overall_score DESC, s.strategy_id
                """;
        List<PendingRow> rows = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                rows.add(readPending(result));
            }
        }
        return rows;
    }

    private static PendingRow readPending(ResultSet result) throws SQLException {
        String reviewedBy = result.getString("reviewed_by");
        OffsetDateTime reviewedAt = result.getObject("reviewed_at", OffsetDateTime.class);
        String reviewNote = result.getString("review_note");
        // wasNull() 只反映“最后一次 get 读到的是不是 SQL NULL”，所以必须紧跟在 getDouble 后面。
        // 先读别的列再回头判空，判的就是那一列了——而 0.0 分与“没打过分”必须分开，
        // 前者是审核者认为它一文不值，后者是还没人看过。
        double rawReviewerScore = result.getDouble("reviewer_score");
        Double reviewerScore = result.wasNull() ? null : rawReviewerScore;
        PreviousReview previous = reviewedBy == null && reviewedAt == null
                && reviewNote == null && reviewerScore == null
                ? null
                : new PreviousReview(reviewedBy, reviewedAt, reviewNote, reviewerScore);
        return new PendingRow(
                result.getString("strategy_id"),
                result.getString("name"),
                result.getString("summary"),
                result.getInt("step_count"),
                result.getLong("chunk_count"),
                result.getString("review_status"),
                result.getBoolean("archive_missing"),
                result.getBoolean("pending_archive"),
                result.getDouble("evidence_score"),
                result.getDouble("effectiveness_score"),
                result.getDouble("community_score"),
                result.getDouble("overall_score"),
                result.getInt("tried_count"),
                result.getInt("helpful_count"),
                previous);
    }

    /** 取审核目标；不在存储层里（例如 strategyId 写错）时返回 empty，由服务层转成 404。 */
    public Optional<ReviewTarget> find(Connection connection, String strategyId) throws SQLException {
        String sql = "SELECT strategy_id, review_status FROM strategies WHERE strategy_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, strategyId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new ReviewTarget(result.getString(1), result.getString(2)))
                        : Optional.empty();
            }
        }
    }

    /**
     * 落一次审核决定，返回数据库认定的审核时间。
     *
     * <p>{@code reviewed_at} 用 {@code NOW()} 而不是应用侧传时间：它和 updated_at 一样是审计事实，
     * 由数据库时钟统一给出才不会因为客户端时区或时钟漂移出现"审核时间早于投稿时间"这类记录。
     *
     * <p>两个输入分用 {@code COALESCE(?, 原值)}：审核者没补分时保持原样，补了才覆盖。
     * 这是导入器警告"档案缺少 evidenceScore 与 effectivenessScore，按 0 导入，需要审核者补分"
     * 的兑现处——在此之前那句警告没有任何地方可以补。
     *
     * <p>不动 exposure_state / pending_archive：投放档位与证伪标记属于反馈消费服务，
     * 而且人工暂停过的策略不应该因为一次"通过"就自动恢复曝光。
     */
    public OffsetDateTime applyDecision(Connection connection, String strategyId, String reviewStatus,
                                        String reviewedBy, String reviewNote, Double reviewerScore,
                                        Double evidenceScore, Double effectivenessScore) throws SQLException {
        String sql = """
                UPDATE strategies SET
                    review_status = ?, reviewed_by = ?, reviewed_at = NOW(),
                    review_note = ?, reviewer_score = ?,
                    evidence_score = COALESCE(?, evidence_score),
                    effectiveness_score = COALESCE(?, effectiveness_score),
                    updated_at = NOW()
                WHERE strategy_id = ?
                RETURNING reviewed_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, reviewStatus);
            statement.setString(2, reviewedBy);
            setNullableText(statement, 3, reviewNote);
            setNullableDouble(statement, 4, reviewerScore);
            setNullableDouble(statement, 5, evidenceScore);
            setNullableDouble(statement, 6, effectivenessScore);
            statement.setString(7, strategyId);
            try (ResultSet result = statement.executeQuery()) {
                // WHERE 命中主键，RETURNING 要么一行要么零行；零行意味着调用前策略被并发删掉了。
                return result.next() ? result.getObject("reviewed_at", OffsetDateTime.class) : null;
            }
        }
    }

    private static void setNullableText(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableDouble(PreparedStatement statement, int index, Double value)
            throws SQLException {
        if (value == null) statement.setNull(index, Types.DOUBLE);
        else statement.setDouble(index, value);
    }
}
