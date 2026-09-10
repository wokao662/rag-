package com.example.rag.recommendation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 尝试后反馈的持久化。这张表是 triedCount / helpfulCount / communityScore 的唯一写入来源，
 * 也是渐进投放升降权的唯一依据——卡片上的点赞只给投稿者看，不参与任何权重计算。
 */
public final class TrialFeedbackRepository {
    /** 试过之后才能给出的三种效果评价。 */
    private static final Set<String> TRIED_OUTCOMES = Set.of("helpful", "partial", "not_helpful");
    /**
     * 没试过的两种原因。not_suitable 表示方法本身没问题但不适合这个用户，
     * 它是适合人群预测模型最有价值的负样本，且不参与降权；no_time 是纯粹的未曝光。
     */
    private static final Set<String> NOT_TRIED_OUTCOMES = Set.of("not_suitable", "no_time");
    private static final int NOTE_MAX_LENGTH = 2000;

    public record TrialFeedback(
            String strategyId,
            boolean tried,
            String outcome,
            String note,
            UUID sourceMessageId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

    /** 一个用户对一个方法只保留一条记录，重复提交视为修改自己之前的反馈。 */
    public TrialFeedback save(
            Connection connection,
            UUID userId,
            String strategyId,
            boolean tried,
            String outcome,
            String note,
            UUID sourceMessageId
    ) throws SQLException {
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId 不能为空");
        }
        validateOutcome(tried, outcome);
        String trimmedNote = normalizeNote(note);
        String normalizedStrategyId = strategyId.trim();
        String sql = """
                INSERT INTO method_trial_feedback
                    (user_id, strategy_id, tried, outcome, note, source_message_id)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, strategy_id) DO UPDATE SET
                    tried = EXCLUDED.tried,
                    outcome = EXCLUDED.outcome,
                    note = EXCLUDED.note,
                    source_message_id = COALESCE(
                        EXCLUDED.source_message_id, method_trial_feedback.source_message_id),
                    updated_at = NOW()
                RETURNING strategy_id, tried, outcome, note, source_message_id, created_at, updated_at
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            statement.setString(2, normalizedStrategyId);
            statement.setBoolean(3, tried);
            statement.setString(4, outcome);
            if (trimmedNote == null) {
                statement.setNull(5, Types.VARCHAR);
            } else {
                statement.setString(5, trimmedNote);
            }
            if (sourceMessageId == null) {
                statement.setNull(6, Types.OTHER);
            } else {
                statement.setObject(6, sourceMessageId);
            }
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("保存尝试后反馈后没有返回记录");
                return read(result);
            }
        }
    }

    /** 读取该用户对全部方法的反馈，键为 strategyId，用于历史页恢复已填写的状态。 */
    public Map<String, TrialFeedback> findByUser(Connection connection, UUID userId) throws SQLException {
        String sql = """
                SELECT strategy_id, tried, outcome, note, source_message_id, created_at, updated_at
                FROM method_trial_feedback
                WHERE user_id = ?
                """;
        Map<String, TrialFeedback> feedback = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    TrialFeedback entry = read(result);
                    feedback.put(entry.strategyId(), entry);
                }
            }
        }
        return feedback;
    }

    private static TrialFeedback read(ResultSet result) throws SQLException {
        return new TrialFeedback(
                result.getString("strategy_id"),
                result.getBoolean("tried"),
                result.getString("outcome"),
                result.getString("note"),
                result.getObject("source_message_id", UUID.class),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class)
        );
    }

    /**
     * tried 与 outcome 必须自洽。数据库层也有同样的复合 CHECK 约束，
     * 这里提前拦截是为了给出可读的中文错误，而不是把 SQLState 抛给前端。
     * 包级可见以便单测：这条约束守的是 triedCount / helpfulCount 的统计质量。
     */
    static void validateOutcome(boolean tried, String outcome) {
        if (outcome == null || outcome.isBlank()) {
            throw new IllegalArgumentException("outcome 不能为空");
        }
        if (tried) {
            if (!TRIED_OUTCOMES.contains(outcome)) {
                throw new IllegalArgumentException(
                        "试过之后的评价只能是 helpful、partial 或 not_helpful，收到：" + outcome);
            }
        } else if (!NOT_TRIED_OUTCOMES.contains(outcome)) {
            throw new IllegalArgumentException(
                    "没有试过时的原因只能是 not_suitable 或 no_time，收到：" + outcome);
        }
    }

    private static String normalizeNote(String note) {
        if (note == null || note.isBlank()) return null;
        String trimmed = note.trim();
        if (trimmed.length() > NOTE_MAX_LENGTH) {
            throw new IllegalArgumentException("补充说明最长 " + NOTE_MAX_LENGTH + " 字");
        }
        return trimmed;
    }
}
