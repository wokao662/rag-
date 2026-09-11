package com.example.rag.recommendation;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 审核输入校验的单元测试。
 *
 * <p>这些静态方法是审核端点的第一道闸门。它们出错的表现是静默的：一个写错的 decision 会变成
 * 数据库 CHECK 约束冲突，对外表现为 503"数据库暂时不可用"，而真正的原因（表单填错）在响应里
 * 一个字都看不到。所以校验本身必须被直接测到，不能只靠端点测试覆盖。
 */
class StrategyReviewServiceTest {

    // ---------- decision ----------

    @Test
    void decisionIsCaseInsensitiveAndTrimmed() {
        // 前端可能送来 "Approved" 或带空白的值。落库的 review_status 被 V6 的 CHECK 限死为五个
        // 小写值，所以大小写与空白必须在这里收口，而不是让它一路走到数据库再炸。
        assertEquals("approved", StrategyReviewService.decisionOf("approved"));
        assertEquals("approved", StrategyReviewService.decisionOf("APPROVED"));
        assertEquals("approved", StrategyReviewService.decisionOf("  Approved  "));
        assertEquals("rejected", StrategyReviewService.decisionOf("Rejected"));
    }

    @Test
    void decisionRejectsValuesOutsideTheReviewVocabulary() {
        // archived 与 draft 都是合法的 review_status，但不是审核端点能做出的决定：
        // 归档须由人确认 pending_archive 标记、走单独的动作，导入态则不该由人手工写回。
        // 放开它们等于让一个端点悄悄变成"任意修改审核状态"的端点。
        for (String invalid : new String[]{"archived", "draft", "pending", "approve", ""}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> StrategyReviewService.decisionOf(invalid),
                    "应当拒绝：" + invalid);
            assertTrue(error.getMessage().contains("approved 或 rejected"),
                    "错误信息要给出可用取值，实际：" + error.getMessage());
        }
    }

    @Test
    void decisionRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> StrategyReviewService.decisionOf(null));
    }

    // ---------- 分数 ----------

    @Test
    void scoreAcceptsNullAsDoNotChange() {
        // null 不是"0 分"，而是"这次不改档案导入的值"。二者混在一起会让审核者
        // 只想给结论时，把 evidence_score 悄悄抹成 0——那正是垫底问题的成因。
        assertNull(StrategyReviewService.scoreOf("reviewerScore", null));
        assertNull(StrategyReviewService.scoreOf("evidenceScore", null));
    }

    @Test
    void scoreAcceptsClosedInterval() {
        assertEquals(0.0, StrategyReviewService.scoreOf("reviewerScore", 0.0));
        assertEquals(1.0, StrategyReviewService.scoreOf("reviewerScore", 1.0));
        assertEquals(0.75, StrategyReviewService.scoreOf("effectivenessScore", 0.75));
    }

    @Test
    void scoreRejectsOutOfRangeWithNameOfField() {
        for (double invalid : new double[]{-0.01, 1.01, 7.5, -3}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> StrategyReviewService.scoreOf("evidenceScore", invalid),
                    "应当拒绝：" + invalid);
            // 错误信息里必须带字段名：一次决定里有三个分数，只说"分数不合法"没法改表单。
            assertTrue(error.getMessage().contains("evidenceScore"),
                    "错误信息要指出是哪个字段，实际：" + error.getMessage());
        }
    }

    // ---------- strategyId 与备注 ----------

    @Test
    void strategyIdIsTrimmedAndRequired() {
        assertEquals("strategy-practice-testing",
                StrategyReviewService.requireStrategyId("  strategy-practice-testing "));
        assertThrows(IllegalArgumentException.class, () -> StrategyReviewService.requireStrategyId(null));
        assertThrows(IllegalArgumentException.class, () -> StrategyReviewService.requireStrategyId("   "));
    }

    @Test
    void strategyIdRejectsOverlongValue() {
        // 列宽是 VARCHAR(128)。不在这里拦，超长值会变成数据库异常而不是 400。
        assertThrows(IllegalArgumentException.class,
                () -> StrategyReviewService.requireStrategyId("x".repeat(129)));
        assertEquals(128, StrategyReviewService.requireStrategyId("x".repeat(128)).length());
    }

    @Test
    void blankNoteBecomesNullInsteadOfEmptyString() {
        // "没留话"在库里应该只有一种样子。存空串会让审核界面分不清
        // "审核者什么都没说"与"审核者说了但被存坏了"，SQL 里也得多写一种判空。
        assertNull(StrategyReviewService.noteOf(null));
        assertNull(StrategyReviewService.noteOf(""));
        assertNull(StrategyReviewService.noteOf("   \n "));
    }

    @Test
    void noteIsTrimmedAndLengthCapped() {
        assertEquals("步骤为空，先补方法", StrategyReviewService.noteOf("  步骤为空，先补方法  "));
        assertThrows(IllegalArgumentException.class,
                () -> StrategyReviewService.noteOf("字".repeat(StrategyReviewService.MAX_NOTE_LENGTH + 1)));
        assertEquals(StrategyReviewService.MAX_NOTE_LENGTH,
                StrategyReviewService.noteOf("字".repeat(StrategyReviewService.MAX_NOTE_LENGTH)).length());
    }
}
