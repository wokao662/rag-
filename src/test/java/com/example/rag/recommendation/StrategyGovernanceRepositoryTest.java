package com.example.rag.recommendation;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 范围过滤子句的构造规则。
 *
 * <p>这里测的是字符串拼接，看着琐碎，但它盯的是一类真实发生过的故障：带范围的重算曾经拼出
 * {@code FROM method_trial_feedback ?,? GROUP BY}——语法错误。而启动时的全量重算走空范围分支，
 * 一切正常，于是这个 bug 只在推荐曝光与反馈提交时才炸，且被 best-effort 的 catch 吃掉，
 * 表现只是"曝光表一直是空的"。
 */
class StrategyGovernanceRepositoryTest {

    @Test
    void emptyScopeMeansNoFilter() {
        // 空范围必须是"不加过滤"，而不是 IN ()——后者在 PostgreSQL 里同样是语法错误。
        assertEquals("", StrategyGovernanceRepository.whereIn(null));
        assertEquals("", StrategyGovernanceRepository.whereIn(List.of()));
    }

    @Test
    void scopedQueryAlwaysCarriesCompleteWhereClause() {
        assertEquals("WHERE strategy_id IN (?)",
                StrategyGovernanceRepository.whereIn(List.of("strategy-rereading")));
        assertEquals("WHERE strategy_id IN (?,?,?)",
                StrategyGovernanceRepository.whereIn(
                        List.of("strategy-rereading", "strategy-imagery", "strategy-summarization")));
    }

    @Test
    void placeholderCountMatchesScopeSize() {
        // bindIds 按顺序从 1 开始绑定，占位符数量对不上就是"参数索引越界"或"漏绑参数"。
        List<String> scope = List.of("a", "b", "c", "d", "e", "f", "g");
        String clause = StrategyGovernanceRepository.whereIn(scope);
        long placeholders = clause.chars().filter(character -> character == '?').count();
        assertEquals(scope.size(), placeholders);
        assertTrue(clause.startsWith("WHERE strategy_id IN ("), "带范围时必须以完整 WHERE 开头：" + clause);
        assertTrue(clause.endsWith(")"), "带范围时必须闭合括号：" + clause);
    }
}
