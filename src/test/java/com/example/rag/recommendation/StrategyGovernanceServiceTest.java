package com.example.rag.recommendation;

import com.example.rag.recommendation.StrategyGovernanceRepository.GateRow;
import com.example.rag.recommendation.StrategyGovernanceRepository.TrialStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渐进投放规则的单元测试。这些静态方法决定了"哪个方法能被推荐、什么时候扩量、什么时候待归档"，
 * 出错的表现是静默的（推荐变少或坏方法继续被推），所以规则本身必须被直接测到。
 */
class StrategyGovernanceServiceTest {

    private static GateRow gate(String reviewStatus, String exposureState,
                                int exposedUserCount, int exposureCap, boolean pendingArchive) {
        return new GateRow("strategy-x", reviewStatus, exposureState, exposedUserCount,
                exposureCap, pendingArchive, false);
    }

    private static GateRow gateWithoutArchive() {
        return new GateRow("strategy-x", "draft", "seed", 0, 50, false, true);
    }

    private static TrialStats trial(int tried, int helpful) {
        return new TrialStats("strategy-x", tried, helpful);
    }

    // ---------- 社区分：Wilson 下界 ----------

    @Test
    void wilsonLowerIsConservativeOnTinySamples() {
        // 1 个人说有用不是 100%：下界把它拉回 0.2 左右，这是取"下界"而非点估计的全部意义。
        assertEquals(0, StrategyGovernanceService.wilsonLower(0, 0, 1.96), 1e-9);
        double oneOfOne = StrategyGovernanceService.wilsonLower(1, 1, 1.96);
        assertTrue(oneOfOne < 0.3, "1/1 的下界应远低于点估计 1.0，实际：" + oneOfOne);
        assertTrue(oneOfOne > 0, "1/1 的下界不应为 0，实际：" + oneOfOne);
    }

    @Test
    void wilsonLowerRisesWithSampleSizeAtSameRate() {
        double tenOfTwenty = StrategyGovernanceService.wilsonLower(10, 20, 1.96);
        double fiveOfTen = StrategyGovernanceService.wilsonLower(5, 10, 1.96);
        // 同样 50% 有用率，样本越多越可信，下界越高。
        assertTrue(tenOfTwenty > fiveOfTen,
                "同样有用率下 20 样本的下界应高于 10 样本：" + tenOfTwenty + " vs " + fiveOfTen);
    }

    @Test
    void wilsonLowerOrdersByHelpfulRate() {
        double high = StrategyGovernanceService.wilsonLower(9, 10, 1.96);
        double middle = StrategyGovernanceService.wilsonLower(5, 10, 1.96);
        double low = StrategyGovernanceService.wilsonLower(1, 10, 1.96);
        assertTrue(high > middle && middle > low, "下界应随有用率单调递增：" + high + "/" + middle + "/" + low);
        assertTrue(high < 1.0 && low > 0.0, "下界必须落在开区间 (0,1) 内");
    }

    @Test
    void communityScoreIsZeroBelowThreshold() {
        // 门槛以下归 0：没有足够的社区证据就不给社区分加成，合成分退回由证据与有效性驱动。
        // 不能沿用库里旧值：用户可以把 tried 从 true 改回 false 使样本量跌破门槛，沿用旧值
        // 会让 community_score 不再是反馈表的纯函数，从零重放全部反馈得不到同一个数。
        assertEquals(0.0, StrategyGovernanceService.communityScore(trial(9, 9)), 1e-9);
        assertEquals(0.0, StrategyGovernanceService.communityScore(null), 1e-9);
    }

    @Test
    void communityScoreAppliesWilsonAtThreshold() {
        double atThreshold = StrategyGovernanceService.communityScore(trial(10, 10));
        assertEquals(StrategyGovernanceService.wilsonLower(10, 10, 1.96), atThreshold, 1e-9);
        assertTrue(atThreshold > 0.0, "达到门槛后必须由真实反馈产生非零社区分");
    }

    @Test
    void partialOutcomeDoesNotCountAsHelpful() {
        // partial 由 SQL 层排除，这里验证的是"只数 helpful"这一保守口径不会被绕过：
        // 10 次试用只有 5 次明确有用，下界应低于 0.5。
        double community = StrategyGovernanceService.communityScore(trial(10, 5));
        assertTrue(community < 0.5, "5/10 的下界应低于点估计 0.5，实际：" + community);
    }

    // ---------- 推荐闸门：审核模式 ----------

    @Test
    void gateAllowsStrategiesNotYetImported() {
        // 导入完成前的过渡：存储层查不到就放行，否则建表当天现有推荐会全部被过滤成空。
        assertTrue(StrategyGovernanceService.allow(null, true));
        assertTrue(StrategyGovernanceService.allow(null, false));
    }

    @Test
    void reviewGateBlocksUnreviewedAndArchived() {
        assertFalse(StrategyGovernanceService.allow(gate("draft", "seed", 0, 50, false), true));
        assertFalse(StrategyGovernanceService.allow(gate("pending", "seed", 0, 50, false), true));
        assertFalse(StrategyGovernanceService.allow(gate("rejected", "seed", 0, 50, false), true));
        assertFalse(StrategyGovernanceService.allow(gate("archived", "full", 0, 50, false), true));
        assertTrue(StrategyGovernanceService.allow(gate("approved", "seed", 0, 50, false), true));
    }

    @Test
    void reviewGateEnforcesSeedCapButNotFullCap() {
        assertTrue(StrategyGovernanceService.allow(
                gate("approved", "seed", StrategyGovernanceService.SEED_CAP - 1, 50, false), true));
        assertFalse(StrategyGovernanceService.allow(
                gate("approved", "seed", StrategyGovernanceService.SEED_CAP, 50, false), true));
        // full 不设上限：已经放量的方法不该因为人数增长被重新挡下。
        assertTrue(StrategyGovernanceService.allow(
                gate("approved", "full", Integer.MAX_VALUE - 1, 50, false), true));
    }

    // ---------- 推荐闸门：审核前的观测模式 ----------

    @Test
    void observeModeLetsDraftThrough() {
        // 13 个档案现在全是 draft，审核完成前开闸门等于把推荐全杀光；
        // 观测模式下不按审核状态也不按人数上限拦，但曝光与反馈照常累计。
        assertTrue(StrategyGovernanceService.allow(gate("draft", "seed", 0, 50, false), false));
        assertTrue(StrategyGovernanceService.allow(
                gate("draft", "seed", Integer.MAX_VALUE - 1, 50, false), false));
        assertTrue(StrategyGovernanceService.allow(gate("rejected", "full", 0, 50, false), false));
    }

    @Test
    void observeModeStillBlocksFalsifiedAndUnarchivedStrategies() {
        // 这三类拦截不依赖审核者：无档案是无溯源，待归档是真实用户证伪，暂停是人工意志。
        assertFalse(StrategyGovernanceService.allow(gateWithoutArchive(), false));
        assertFalse(StrategyGovernanceService.allow(gate("approved", "full", 0, 50, true), false));
        assertFalse(StrategyGovernanceService.allow(gate("approved", "paused", 0, 50, false), false));
    }

    @Test
    void reviewGateAlsoBlocksFalsifiedAndUnarchived() {
        // 开启审核闸门不得放松上面三类：待归档的策略即使已审核通过也不能继续曝光。
        assertFalse(StrategyGovernanceService.allow(gateWithoutArchive(), true));
        assertFalse(StrategyGovernanceService.allow(gate("approved", "full", 0, 50, true), true));
        assertFalse(StrategyGovernanceService.allow(gate("approved", "paused", 0, 50, false), true));
    }

    @Test
    void tierCapFollowsExposureState() {
        assertEquals(StrategyGovernanceService.SEED_CAP,
                StrategyGovernanceService.tierCap(gate("approved", "seed", 0, 50, false)));
        assertEquals(50, StrategyGovernanceService.tierCap(gate("approved", "scaling", 0, 50, false)));
        assertEquals(Integer.MAX_VALUE,
                StrategyGovernanceService.tierCap(gate("approved", "full", 0, 50, false)));
    }

    // ---------- 扩量与归档 ----------

    @Test
    void seedPromotesOnlyWhenCapReachedAndFeedbackReady() {
        GateRow reached = gate("approved", "seed", StrategyGovernanceService.SEED_CAP, 50, false);
        GateRow notReached = gate("approved", "seed", StrategyGovernanceService.SEED_CAP - 1, 50, false);

        assertEquals("scaling", StrategyGovernanceService.nextExposureState(
                reached, trial(StrategyGovernanceService.MIN_FEEDBACK, 10), 0.8));
        // 人数够了但反馈不足门槛：留在 seed 继续收集，不能凭曝光量扩量。
        assertEquals("seed", StrategyGovernanceService.nextExposureState(
                reached, trial(StrategyGovernanceService.MIN_FEEDBACK - 1, 9), 0.8));
        // 反馈够但社区分低于扩量线：同样留在原档。
        assertEquals("seed", StrategyGovernanceService.nextExposureState(
                reached, trial(StrategyGovernanceService.MIN_FEEDBACK, 3), 0.2));
        assertEquals("seed", StrategyGovernanceService.nextExposureState(notReached, trial(30, 30), 0.9));
        assertEquals("seed", StrategyGovernanceService.nextExposureState(reached, null, 0));
    }

    @Test
    void scalingPromotesToFullAndTerminalStatesStay() {
        GateRow scaling = gate("approved", "scaling", 50, 50, false);
        assertEquals("full", StrategyGovernanceService.nextExposureState(
                scaling, trial(StrategyGovernanceService.MIN_FEEDBACK, 10), 0.9));
        assertEquals("scaling", StrategyGovernanceService.nextExposureState(
                gate("approved", "scaling", 10, 50, false), trial(30, 30), 0.9));
        // full 与 paused 是终态：paused 只能由人工恢复，自动逻辑不许把它放开。
        assertEquals("full", StrategyGovernanceService.nextExposureState(
                gate("approved", "full", 999, 50, false), trial(30, 30), 0.9));
        assertEquals("paused", StrategyGovernanceService.nextExposureState(
                gate("approved", "paused", 0, 50, false), trial(30, 30), 0.9));
    }

    @Test
    void pendingArchiveNeedsBothEnoughSamplesAndLowScore() {
        assertTrue(StrategyGovernanceService.pendingArchive(
                trial(StrategyGovernanceService.MIN_FEEDBACK, 0), 0.1));
        // 样本不足不归档：3 个人说没用不足以判一个方法死刑。
        assertFalse(StrategyGovernanceService.pendingArchive(
                trial(StrategyGovernanceService.MIN_FEEDBACK - 1, 0), 0.0));
        assertFalse(StrategyGovernanceService.pendingArchive(
                trial(30, 20), StrategyGovernanceService.LOW_COMMUNITY));
        assertFalse(StrategyGovernanceService.pendingArchive(null, 0.0));
    }

    @Test
    void overallScoreWeightsSumToOne() {
        assertEquals(1.0, StrategyGovernanceService.overallScore(1, 1, 1), 1e-9);
        assertEquals(0.0, StrategyGovernanceService.overallScore(0, 0, 0), 1e-9);
        // 社区反馈权重最高：真实用户试用结果比文献证据更能决定排序。
        assertEquals(StrategyGovernanceService.WEIGHT_COMMUNITY,
                StrategyGovernanceService.overallScore(0, 1, 0), 1e-9);
        assertEquals(StrategyGovernanceService.WEIGHT_EVIDENCE,
                StrategyGovernanceService.overallScore(1, 0, 0), 1e-9);
        assertEquals(StrategyGovernanceService.WEIGHT_EFFECTIVENESS,
                StrategyGovernanceService.overallScore(0, 0, 1), 1e-9);
        assertEquals(1.0, StrategyGovernanceService.WEIGHT_EVIDENCE
                + StrategyGovernanceService.WEIGHT_COMMUNITY
                + StrategyGovernanceService.WEIGHT_EFFECTIVENESS, 1e-9);
    }
}
