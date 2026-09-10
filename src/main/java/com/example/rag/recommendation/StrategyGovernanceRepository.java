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

    /**
     * 合成总分需要的两个输入分。它们由导入器与审核者写，反馈消费只读不写。
     *
     * <p>这里刻意不读 {@code community_score}：它是本服务自己写出去的派生值，读回来再写回去
     * 就等于让公式依赖自己的上一次输出。那样一旦样本量从门槛上跌回门槛下（用户可以把
     * {@code tried} 改回 false），旧值就会永久停在库里，从零重放反馈再也得不到它。
     * 不读它，重算才真的是事实表的纯函数。
     */
    public record ScoreRow(String strategyId, double evidenceScore, double effectivenessScore) {
    }

    /**
     * 从尝试后反馈聚合出每个策略的试用与有用次数，是社区分的唯一数据来源。
     *
     * @param strategyIds 限定范围；null 或空表示全部策略
     */
    public Map<String, TrialStats> trialStats(Connection connection, Collection<String> strategyIds)
            throws SQLException {
        String sql = """
                SELECT strategy_id,
                       COUNT(*) FILTER (WHERE tried) AS tried_count,
                       COUNT(*) FILTER (WHERE tried AND outcome = 'helpful') AS helpful_count
                FROM method_trial_feedback
                %s
                GROUP BY strategy_id
                """.formatted(whereIn(strategyIds));
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

    /**
     * 范围过滤子句；范围为空时返回空串，即不加过滤（全表重算）。
     *
     * <p>只保留这一个构造入口，不再另提供"只返回占位符串"的变体：裸占位符拼进
     * {@code FROM ... GROUP BY} 之间是语法错误，而这类错误只在带范围重算时出现
     * （启动时的全量重算走空过滤分支，跑得过），单测很难发现。
     */
    static String whereIn(Collection<String> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) return "";
        String placeholders = String.join(",", Collections.nCopies(strategyIds.size(), "?"));
        return "WHERE strategy_id IN (" + placeholders + ")";
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
     * 记录曝光。同一用户对同一策略只计一次，否则重复推荐会把人数上限刷爆，
     * 渐进投放就失去意义。
     *
     * <p>这里只写入事实，不刷新 exposed_user_count：人数是从本表推导的派生值，
     * 归 {@link #refreshExposedUserCounts} 统一重算，免得同一公式存在两份。
     */
    public void recordExposures(Connection connection, UUID userId, Collection<String> strategyIds)
            throws SQLException {
        if (userId == null || strategyIds == null || strategyIds.isEmpty()) return;
        String insert = """
                INSERT INTO strategy_exposures (strategy_id, user_id)
                VALUES (?, ?)
                ON CONFLICT (strategy_id, user_id) DO NOTHING
                """;
        for (String strategyId : strategyIds) {
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setString(1, strategyId);
                statement.setObject(2, userId);
                statement.executeUpdate();
            }
        }
    }

    /**
     * 从曝光表重建去重后的人数。
     *
     * <p>必须在看闸门快照之前执行：升档判定要拿它跟当前档位的人数上限比，
     * 读旧值就会晚一轮才扩量。
     *
     * @param strategyIds 限定范围；null 或空表示全部策略
     */
    public void refreshExposedUserCounts(Connection connection, Collection<String> strategyIds)
            throws SQLException {
        String sql = """
                UPDATE strategies SET
                    exposed_user_count = (
                        SELECT COUNT(*) FROM strategy_exposures e
                        WHERE e.strategy_id = strategies.strategy_id)
                %s
                """.formatted(whereIn(strategyIds));
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindIds(statement, strategyIds);
            statement.executeUpdate();
        }
    }

    /**
     * 读取策略的两个输入分，供重算合成总分时使用。
     *
     * @param strategyIds 限定范围；null 或空表示全部策略
     */
    public Map<String, ScoreRow> currentScores(Connection connection, Collection<String> strategyIds)
            throws SQLException {
        String sql = """
                SELECT strategy_id, evidence_score, effectiveness_score
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
                            result.getDouble("effectiveness_score"));
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
