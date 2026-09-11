package com.example.rag.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 行为观测的读写。全部指标由 SQL 聚合算出，Java 只负责合成比率，
 * 因此这些值是确定性代码的产物，不含任何模型判断。
 *
 * <p>这些数字的用途是给"用户自述"提供客观对照：自述"我每天都在学"对上近 7 天实际活跃天数，
 * 自述"我试过那个方法"对上真的提交过尝试后反馈的方法数。无意识失真在对话内部往往完全自洽，
 * 只有与行为对照才显形——这张表就是那个对照面。
 *
 * <p><b>按 2026-09-10 的隐私决定，这里的数据不进入任何模型输入，也不写入 model_call_logs。</b>
 * 采集与使用解耦：现在照采照存，将来若要接入模型一，只给已经聚合好的偏差信号，不给原始行为日志。
 */
public final class BehaviorObservationRepository {
    /** 活跃天数的自然日按 UTC 切分。指标是粗粒度的，不为它引入用户时区。 */
    private static final String MESSAGE_SQL = """
            SELECT
                COUNT(*) FILTER (WHERE m.role = 'user') AS user_messages_all,
                COUNT(*) FILTER (WHERE m.role = 'user'
                    AND m.created_at >= NOW() - INTERVAL '7 days') AS user_messages_7,
                COUNT(*) FILTER (WHERE m.role = 'user'
                    AND m.created_at >= NOW() - INTERVAL '30 days') AS user_messages_30,
                COUNT(DISTINCT date_trunc('day', m.created_at)) FILTER (WHERE m.role = 'user'
                    AND m.created_at >= NOW() - INTERVAL '7 days') AS active_days_7,
                COUNT(DISTINCT date_trunc('day', m.created_at)) FILTER (WHERE m.role = 'user'
                    AND m.created_at >= NOW() - INTERVAL '30 days') AS active_days_30,
                COUNT(*) FILTER (WHERE m.role = 'assistant'
                    AND m.metadata_json ->> 'messageType' = 'recommendation') AS recommendations,
                COALESCE(EXTRACT(EPOCH FROM (
                    MAX(m.created_at) FILTER (WHERE m.role = 'user')
                    - MIN(m.created_at) FILTER (WHERE m.role = 'user'))) / 86400, 0) AS span_days
            FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            WHERE c.user_id = ?
            """;

    private static final String TRIAL_SQL = """
            SELECT COUNT(*) AS feedback_given,
                   COUNT(*) FILTER (WHERE tried) AS methods_tried,
                   COUNT(*) FILTER (WHERE tried AND outcome = 'helpful') AS helpful
            FROM method_trial_feedback
            WHERE user_id = ?
            """;

    /**
     * 被推荐过的不同方法数，是跟进率的分母。
     *
     * <p>不能用推荐消息数当分母：一条消息里通常有好几个方法，而 method_trial_feedback 有
     * UNIQUE(user_id, strategy_id)，反馈数天然是去重后的方法数。两边量级不一致会让
     * “率”超过 1。分子分母都数方法，跟进率才恒定在 [0, 1]：提交反馈时 wasRecommendedTo
     * 已保证只能对被推荐过的方法反馈，分子集合是分母集合的子集。
     *
     * <p>jsonb_typeof 守卫不可省：旧消息或异常写入使 recommendations 不是数组时，
     * jsonb_array_elements 会直接报错，而采集失败是静默的——表现只是该用户的观测值从此不再更新。
     */
    private static final String RECOMMENDED_STRATEGIES_SQL = """
            SELECT COUNT(DISTINCT element ->> 'strategyId') AS recommended_strategies
            FROM messages m
            JOIN conversations c ON c.id = m.conversation_id
            CROSS JOIN LATERAL jsonb_array_elements(
                m.metadata_json -> 'recommendation' -> 'recommendations') AS element
            WHERE c.user_id = ?
              AND m.metadata_json ->> 'messageType' = 'recommendation'
              AND jsonb_typeof(m.metadata_json -> 'recommendation' -> 'recommendations') = 'array'
            """;

    private static final String UPSERT_SQL = """
            INSERT INTO behavior_observations (user_id, metric, window_days, metric_value, computed_at)
            VALUES (?, ?, ?, ?, NOW())
            ON CONFLICT (user_id, metric, window_days) DO UPDATE SET
                metric_value = EXCLUDED.metric_value,
                computed_at = NOW()
            """;

    /** 一条观测值。windowDays 为 0 表示不设窗口，统计全量历史。 */
    public record Observation(String metric, int windowDays, double value) {
    }

    /** 算出该用户当前的全部观测指标。 */
    public List<Observation> compute(Connection connection, UUID userId) throws SQLException {
        long userMessagesAll;
        long userMessages7;
        long userMessages30;
        long activeDays7;
        long activeDays30;
        long recommendations;
        double spanDays;

        try (PreparedStatement statement = connection.prepareStatement(MESSAGE_SQL)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("行为观测查询没有返回行");
                userMessagesAll = result.getLong("user_messages_all");
                userMessages7 = result.getLong("user_messages_7");
                userMessages30 = result.getLong("user_messages_30");
                activeDays7 = result.getLong("active_days_7");
                activeDays30 = result.getLong("active_days_30");
                recommendations = result.getLong("recommendations");
                spanDays = result.getDouble("span_days");
            }
        }

        long feedbackGiven;
        long methodsTried;
        long helpful;
        try (PreparedStatement statement = connection.prepareStatement(TRIAL_SQL)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("尝试后反馈聚合没有返回行");
                feedbackGiven = result.getLong("feedback_given");
                methodsTried = result.getLong("methods_tried");
                helpful = result.getLong("helpful");
            }
        }

        long recommendedStrategies;
        try (PreparedStatement statement = connection.prepareStatement(RECOMMENDED_STRATEGIES_SQL)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("被推荐方法数聚合没有返回行");
                recommendedStrategies = result.getLong("recommended_strategies");
            }
        }

        List<Observation> observations = new ArrayList<>();
        observations.add(new Observation("user_messages", 0, userMessagesAll));
        observations.add(new Observation("user_messages", 7, userMessages7));
        observations.add(new Observation("user_messages", 30, userMessages30));
        observations.add(new Observation("active_days", 7, activeDays7));
        observations.add(new Observation("active_days", 30, activeDays30));
        observations.add(new Observation("conversation_span_days", 0, spanDays));
        observations.add(new Observation("recommendations_received", 0, recommendations));
        observations.add(new Observation("recommended_strategies", 0, recommendedStrategies));
        observations.add(new Observation("trial_feedback_given", 0, feedbackGiven));
        observations.add(new Observation("methods_tried", 0, methodsTried));
        // 两个比率是失真觉察最直接的对照面：被推荐了方法却从不回来反馈，与自述"我试过了"矛盾；
        // 试过之后说有用的比例，与自述"这个方法很适合我"矛盾。分母为 0 时记 0，不记空值。
        observations.add(new Observation("trial_follow_through_rate", 0,
                rate(feedbackGiven, recommendedStrategies)));
        observations.add(new Observation("helpful_rate", 0, rate(helpful, methodsTried)));
        return observations;
    }

    /** 写入观测值。重算走 upsert，同一指标同一窗口只保留最新值，不堆历史。 */
    public void save(Connection connection, UUID userId, List<Observation> observations)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(UPSERT_SQL)) {
            for (Observation observation : observations) {
                statement.setObject(1, userId);
                statement.setString(2, observation.metric());
                statement.setInt(3, observation.windowDays());
                statement.setDouble(4, observation.value());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static double rate(long numerator, long denominator) {
        return denominator <= 0 ? 0 : (double) numerator / denominator;
    }
}
