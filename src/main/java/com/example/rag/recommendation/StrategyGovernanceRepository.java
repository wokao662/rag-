package com.example.rag.recommendation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 策略治理的持久化：推荐闸门、曝光去重、反馈分数重算。
 *
 * <p>这张表族是渐进投放的执行层。此前 reviewStatus 只写在 Qdrant payload 里而 Java 从不读取，
 * 检索也不带任何过滤，等于没有闸门；反馈计数也无人写入。这里把两者接上。
 */
public final class StrategyGovernanceRepository {

    /** 一个策略的反馈聚合。helpful 只统计 outcome='helpful'，partial 视为未明确有用，保持保守。 */
    public record TrialStats(String strategyId, int triedCount, int helpfulCount) {
    }

    /** 闸门判定需要的策略状态快照。 */
    public record GateRow(
            String strategyId,
            String reviewStatus,
            String exposureState,
            int exposedUserCount,
            int exposureCap,
            boolean pendingArchive,
            boolean archiveMissing
    ) {
    }

    /** 合成总分需要的三个输入分。evidence 与 effectiveness 不由反馈消费写入，这里只读。 */
    public record ScoreRow(String strategyId, double evidenceScore, double effectivenessScore,
                           double communityScore) {
    }

    /**
     * 从尝试后反馈聚合出每个策略的试用与有用次数，是社区分的唯一数据来源。
     *
     * @param strategyIds 限定范围；null 或空表示全部策略
     */
    public Map<String, TrialStats> trialStats(Connection connection, Collection<String> strategyIds)
            throws SQLException {
        String filter = inClause(strategyIds);
        String sql = """
                SELECT strategy_id,
                       COUNT(*) FILTER (WHERE tried) AS tried_count,
                       COUNT(*) FILTER (WHERE tried AND outcome = 'helpful') AS helpful_count
                FROM method_trial_feedback
                %s
                GROUP BY strategy_id
                """.formatted(filter);
        Map<String, TrialStats> stats = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, strategyIds);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    TrialStats row = new TrialStats(
                            result.getString("strategy_id"),
                            result.getInt("tried_count"),
                            result.getInt("helpful_count"));
                    stats.put(row.strategyId(), row);
                }
            }
        }
        return stats;
    }

    /**
     * 读取策略的治理状态。不在结果里的 strategyId 表示尚未导入存储层。
     *
     * @param strategyIds 限定范围；null 或空表示全部策略
     */
    public Map<String, GateRow> gateRows(Connection connection, Collection<String> strategyIds)
            throws SQLException {
        String sql = """
                SELECT strategy_id, review_status, exposure_state,
                       exposed_user_count, exposure_cap, pending_archive, archive_missing
                FROM strategies
                %s
                """.formatted(whereIn(strategyIds));
        Map<String, GateRow> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, strategyIds);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    rows.put(result.getString("strategy_id"), readGate(result));
                }
            }
        }
        return rows;
    }

    private static GateRow readGate(ResultSet result) throws SQLException {
        return new GateRow(
                result.getString("strategy_id"),
                result.getString("review_status"),
                result.getString("exposure_state"),
                result.getInt("exposed_user_count"),
                result.getInt("exposure_cap"),
                result.getBoolean("pending_archive"),
                result.getBoolean("archive_missing"));
    }

    /** IN 子句的占位符串；范围为空时返回空串，即不加过滤。 */
    private static String inClause(Collection<String> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) return "";
        return String.join(",", Collections.nCopies(strategyIds.size(), "?"));
    }

    private static String whereIn(Collection<String> strategyIds) {
        String clause = inClause(strategyIds);
        return clause.isEmpty() ? "" : "WHERE strategy_id IN (" + clause + ")";
    }

    private static void bindIds(PreparedStatement statement, Collection<String> strategyIds)
            throws SQLException {
        if (strategyIds == null || strategyIds.isEmpty()) return;
        int index = 1;
        for (String id : strategyIds) {
            statement.setString(index++, id);
        }
    }

    /**
     * 记录曝光并刷新去重后的人数。同一用户对同一策略只计一次，
     * 否则重复推荐会把人数上限刷爆，渐进投放就失去意义。
     */
    public void recordExposures(Connection connection, UUID userId, Collection<String> strategyIds)
            throws SQLException {
        if (userId == null || strategyIds == null || strategyIds.isEmpty()) return;
        String insert = """
                INSERT INTO strategy_exposures (strategy_id, user_id)
                VALUES (?, ?)
                ON CONFLICT (strategy_id, user_id) DO NOTHING
                """;
        String refresh = """
                UPDATE strategies SET
                    exposed_user_count = (
                        SELECT COUNT(*) FROM strategy_exposures e
                        WHERE e.strategy_id = strategies.strategy_id),
                    updated_at = NOW()
                WHERE strategy_id = ?
                """;
        for (String strategyId : strategyIds) {
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, strategyId);
                statement.setObject(2, userId);
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement(refresh)) {
                statement.setString(1, strategyId);
                statement.executeUpdate();
            }
        }
    }

    /**
     * 读取策略的三个输入分，供重算合成总分时使用。
     *
     * @param strategyIds 限定范围；null 或空表示全部策略
     */
    public Map<String, ScoreRow> currentScores(Connection connection, Collection<String> strategyIds)
            throws SQLException {
        String sql = """
                SELECT strategy_id, evidence_score, effectiveness_score, community_score
                FROM strategies
                %s
                """.formatted(whereIn(strategyIds));
        Map<String, ScoreRow> rows = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, strategyIds);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    ScoreRow row = new ScoreRow(
                            result.getString("strategy_id"),
                            result.getDouble("evidence_score"),
                            result.getDouble("effectiveness_score"),
                            result.getDouble("community_score"));
                    rows.put(row.strategyId(), row);
                }
            }
        }
        return rows;
    }

    /** 写入社区分、试用计数与合成总分。community/overall/tried/helpful 的唯一写入者。 */
    public void applyScores(Connection connection, String strategyId, int triedCount, int helpfulCount,
                            double communityScore, double overallScore, boolean pendingArchive)
            throws SQLException {
        String sql = """
                UPDATE strategies SET
                    tried_count = ?, helpful_count = ?,
                    community_score = ?, overall_score = ?,
                    pending_archive = ?, updated_at = NOW()
                WHERE strategy_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, triedCount);
            statement.setInt(2, helpfulCount);
            statement.setDouble(3, communityScore);
            statement.setDouble(4, overallScore);
            statement.setBoolean(5, pendingArchive);
            statement.setString(6, strategyId);
            statement.executeUpdate();
        }
    }

    /** 调整投放范围状态。 */
    public void setExposureState(Connection connection, String strategyId, String exposureState)
            throws SQLException {
        String sql = "UPDATE strategies SET exposure_state = ?, updated_at = NOW() WHERE strategy_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, exposureState);
            statement.setString(2, strategyId);
            statement.executeUpdate();
        }
    }
}
