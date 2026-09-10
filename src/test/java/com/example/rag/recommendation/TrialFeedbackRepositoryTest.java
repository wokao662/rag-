package com.example.rag.recommendation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * tried 与 outcome 的自洽校验。这条约束守的是 triedCount / helpfulCount 的统计质量：
 * 若允许「没试过却评价很有用」，渐进投放的升降权就会被凭空产生的正面数据推高。
 */
class TrialFeedbackRepositoryTest {
    @ParameterizedTest
    @CsvSource({
            "true, helpful",
            "true, partial",
            "true, not_helpful",
            "false, not_suitable",
            "false, no_time"
    })
    void acceptsSelfConsistentCombinations(boolean tried, String outcome) {
        assertDoesNotThrow(() -> TrialFeedbackRepository.validateOutcome(tried, outcome));
    }

    @ParameterizedTest
    @CsvSource({
            // 没试过就不能评价效果，否则 helpfulCount 会被凭空推高
            "false, helpful",
            "false, partial",
            "false, not_helpful",
            // 试过之后必须给出效果，不能拿「没时间」当理由
            "true, not_suitable",
            "true, no_time"
    })
    void rejectsContradictoryCombinations(boolean tried, String outcome) {
        assertThrows(IllegalArgumentException.class,
                () -> TrialFeedbackRepository.validateOutcome(tried, outcome));
    }

    @Test
    void rejectsMissingOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> TrialFeedbackRepository.validateOutcome(true, null));
        assertThrows(IllegalArgumentException.class,
                () -> TrialFeedbackRepository.validateOutcome(true, "   "));
    }

    @Test
    void rejectsUnknownOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> TrialFeedbackRepository.validateOutcome(true, "amazing"));
    }
}
