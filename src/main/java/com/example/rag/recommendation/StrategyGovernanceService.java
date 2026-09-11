package com.example.rag.recommendation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 渐进投放的执行层：推荐闸门、曝光去重、反馈升降权。
 *
 * <p>此前 reviewStatus 只是 Qdrant payload 里的记录、检索不带任何过滤，draft 内容直接对外服务；
 * triedCount/helpfulCount 全库为 0 且无人写入。这里把"预留字段"变成真正参与运行时判定的闸门，
 * 并让尝试后反馈真正驱动推荐度——飞轮缺的那一半。
 *
 * <p>升降权只用确定性规则（Wilson 下界 + 最小反馈门槛 + 人数上限），可解释、可审计，
 * 不引入学习型排序模型。
 */
@Service
public class StrategyGovernanceService {
    /** 低于该试用次数不动权重：小样本的社区分抖动太大，会误杀或误捧方法。 */
    static final int MIN_FEEDBACK = 10;
    /** Wilson 区间 95% 置信对应的 z 值。 */
    static final double WILSON_Z = 1.96;
    /** seed 档位的曝光人数上限，达到且反馈达标才扩量。 */
    static final int SEED_CAP = 20;
    /** 社区分低于该值且样本足够时标记待归档，但不自动删除——归档须人审确认。 */
    static final double LOW_COMMUNITY = 0.3;
    /** 扩量所需的最低社区分。 */
    static final double PROMOTE_COMMUNITY = 0.5;
    /** 合成总分权重：文献证据 / 社区反馈 / 审核或模型二的有效性。 */
    static final double WEIGHT_EVIDENCE = 0.3;
    static final double WEIGHT_COMMUNITY = 0.5;
    static final double WEIGHT_EFFECTIVENESS = 0.2;

    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final boolean reviewGate;
    private final StrategyGovernanceRepository governance = new StrategyGovernanceRepository();

    /**
     * @param reviewGate 审核闸门是否生效。现存 13 个策略档案全是 draft，开启会把推荐过滤成全空，
     *                   因此人工审核完成前保持关闭；关闭时仍拦“无档案”与“被真实反馈证伪”的策略。
     */
    public StrategyGovernanceService(
            DataSource dataSource,
            TransactionTemplate transactions,
            @Value("${app.governance.review-gate:false}") boolean reviewGate
    ) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.reviewGate = reviewGate;
    }

    /**
     * 应用启动后重算一次全部策略的派生分。
     *
     * <p>community_score / overall_score / pending_archive 都是从反馈表推导出来的派生值，
     * 导入器与人工改分都不应该自己算一遍（那会把公式复制成两份）。启动时重算一次，
     * 保证派生值永远能从 method_trial_feedback 重建。失败不阻止启动：它是可重算的缓存。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void recalculateOnStartup() {
        try {
            recalculateScores();
            System.out.println("策略推荐分已根据尝试后反馈重算（审核闸门："
                    + (reviewGate ? "已开启" : "关闭，只拦无档案与已证伪的策略") + "）");
        } catch (RuntimeException error) {
            // 打整个栈：内层抛的是 DataAccessResourceFailureException("数据库操作失败")，
            // 只取 message 会印出一句没有任何信息的话，而真正的 SQL 错误在 cause 里。
            // 启动重算失败意味着全部派生分停在旧值且无线索可查。
            System.err.println("启动时重算策略推荐分失败，不影响启动：" + error.getMessage());
            error.printStackTrace(System.err);
        }
    }

    /** 召回结果里允许继续参与推荐的策略。不在存储层的策略暂时放行（导入完成前的过渡）。 */
    public Set<String> recommendable(Collection<String> strategyIds) {
        return inTransaction(connection -> {
            Map<String, StrategyGovernanceRepository.GateRow> rows =
                    governance.gateRows(connection, strategyIds);
            Set<String> allowed = new HashSet<>();
            for (String id : strategyIds) {
                if (allow(rows.get(id), reviewGate)) allowed.add(id);
            }
            return allowed;
        });
    }

    /** 记录一次推荐曝光，并顺带重算这几个策略的人数、分数与投放档位（人数变化可能触发扩量）。 */
    public void recordExposure(UUID userId, Collection<String> strategyIds) {
        if (strategyIds == null || strategyIds.isEmpty()) return;
        inTransaction(connection -> {
            governance.recordExposures(connection, userId, strategyIds);
            recalculateWithin(connection, strategyIds);
            return null;
        });
    }

    /** 重算单个策略的分数与档位。反馈提交后调用，范围限定到该策略，不扫全表。 */
    public void recalculate(String strategyId) {
        if (strategyId == null || strategyId.isBlank()) return;
        inTransaction(connection -> {
            recalculateWithin(connection, List.of(strategyId));
            return null;
        });
    }

    /** 重算全部策略的社区分、合成总分、待归档标记与投放档位。导入或人工改分后调用。 */
    public void recalculateScores() {
        inTransaction(connection -> {
            recalculateWithin(connection, null);
            return null;
        });
    }

    /**
     * @param scope 限定重算范围；null 表示全部策略
     */
    private void recalculateWithin(Connection connection, Collection<String> scope) throws SQLException {
        // 人数先重建再读快照：nextExposureState 要拿它跟档位上限比，读旧值会晚一轮才扩量。
        governance.refreshExposedUserCounts(connection, scope);
        Map<String, StrategyGovernanceRepository.TrialStats> stats = governance.trialStats(connection, scope);
        Map<String, StrategyGovernanceRepository.ScoreRow> scores = governance.currentScores(connection, scope);
        Map<String, StrategyGovernanceRepository.GateRow> gates = governance.gateRows(connection, scope);

        for (Map.Entry<String, StrategyGovernanceRepository.ScoreRow> entry : scores.entrySet()) {
            String strategyId = entry.getKey();
            StrategyGovernanceRepository.ScoreRow score = entry.getValue();
            StrategyGovernanceRepository.TrialStats trial = stats.get(strategyId);

            int tried = trial == null ? 0 : trial.triedCount();
            int helpful = trial == null ? 0 : trial.helpfulCount();
            double community = communityScore(trial);
            double overall = overallScore(score.evidenceScore(), community, score.effectivenessScore());
            boolean pending = pendingArchive(trial, community);
            governance.applyScores(connection, strategyId, tried, helpful, community, overall, pending);

            StrategyGovernanceRepository.GateRow gate = gates.get(strategyId);
            if (gate != null) {
                String next = nextExposureState(gate, trial, community);
                if (!next.equals(gate.exposureState())) {
                    governance.setExposureState(connection, strategyId, next);
                }
            }
        }
    }

    /**
     * 闸门规则。row 为 null 表示策略尚未导入存储层：导入完成前暂时放行，
     * 否则建表当天就会把现有推荐全部过滤成空。导入完成后此过渡自然消失。
     *
     * <p>两类拦截与审核无关，因此 reviewGate 关闭时仍然生效：
     * <ul>
     *   <li>{@code archiveMissing}：在 Qdrant 对外服务却没有源档案，无溯源可查；</li>
     *   <li>{@code pendingArchive}：足够多真实用户试过且有用率过低，不需要审核者就该停止曝光。</li>
     * </ul>
     * 而 {@code reviewStatus} 与曝光人数上限属于审核后的渐进投放，只在 reviewGate 开启时生效。
     */
    static boolean allow(StrategyGovernanceRepository.GateRow row, boolean reviewGate) {
        if (row == null) return true;
        if (row.archiveMissing()) return false;
        if (row.pendingArchive()) return false;
        if ("paused".equals(row.exposureState())) return false;
        if (!reviewGate) return true;
        if (!"approved".equals(row.reviewStatus())) return false;
        return row.exposedUserCount() < tierCap(row);
    }

    /** 当前投放档位对应的曝光人数上限；full 不设上限。 */
    static int tierCap(StrategyGovernanceRepository.GateRow row) {
        return switch (row.exposureState()) {
            case "seed" -> SEED_CAP;
            case "scaling" -> row.exposureCap();
            case "full" -> Integer.MAX_VALUE;
            default -> 0;
        };
    }

    /** 达到当前档上限且反馈达标才升档；不达标就留在原档继续收集反馈。 */
    static String nextExposureState(StrategyGovernanceRepository.GateRow row,
                                    StrategyGovernanceRepository.TrialStats trial, double community) {
        int tried = trial == null ? 0 : trial.triedCount();
        boolean feedbackReady = tried >= MIN_FEEDBACK && community >= PROMOTE_COMMUNITY;
        return switch (row.exposureState()) {
            case "seed" -> row.exposedUserCount() >= SEED_CAP && feedbackReady ? "scaling" : "seed";
            case "scaling" -> row.exposedUserCount() >= row.exposureCap() && feedbackReady ? "full" : "scaling";
            default -> row.exposureState();
        };
    }

    /**
     * 社区分 = 有用率的 Wilson 置信下界。取"下界"而非点估计，是因为小样本时点估计过于乐观：
     * 1 个人说有用就是 100%，下界则会把它拉回保守值。
     *
     * <p>样本不足门槛时归 0，而不是沿用库里旧值。归 0 的含义是"没有足够的社区证据，就不给
     * 社区分加成"，此时合成分完全由证据与有效性驱动——这正是新导入策略的实际状态（导入器
     * 拒绝写入档案里那些 triedCount=0 却带着 0.5 的占位 communityScore）。
     *
     * <p>沿用旧值看似保守，实际上把函数变成了非纯的：用户可以把 {@code tried} 从 true 改回
     * false 使样本量跌破门槛，旧值就会永久停在库里，从零重放全部反馈得不到同一个数。不取
     * 库里的值，就没什么可沿用，{@code community_score} 也才真的只是反馈表的函数。
     */
    static double communityScore(StrategyGovernanceRepository.TrialStats trial) {
        if (trial == null || trial.triedCount() < MIN_FEEDBACK) return 0;
        return wilsonLower(trial.helpfulCount(), trial.triedCount(), WILSON_Z);
    }

    static double wilsonLower(int positive, int total, double z) {
        if (total <= 0) return 0;
        double p = (double) positive / total;
        double n = total;
        double denominator = 1 + z * z / n;
        double centre = p + z * z / (2 * n);
        double spread = z * Math.sqrt(p * (1 - p) / n + z * z / (4 * n * n));
        return (centre - spread) / denominator;
    }

    static double overallScore(double evidence, double community, double effectiveness) {
        return WEIGHT_EVIDENCE * evidence + WEIGHT_COMMUNITY * community + WEIGHT_EFFECTIVENESS * effectiveness;
    }

    /** 样本足够且社区分过低时标记待归档。只标记不删除：归档须人审确认。 */
    static boolean pendingArchive(StrategyGovernanceRepository.TrialStats trial, double community) {
        return trial != null && trial.triedCount() >= MIN_FEEDBACK && community < LOW_COMMUNITY;
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
}
