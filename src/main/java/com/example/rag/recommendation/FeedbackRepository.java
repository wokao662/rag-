package com.example.rag.recommendation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FeedbackRepository {
    /**
     * 卡片上的反馈只剩单向点赞。取消「不感兴趣」是因为随手点踩会让好方法
     * 仅因为被推给了不合适的人而降权；负面判断改由 method_trial_feedback 的
     * not_suitable 承载，那是用户在历史页有上下文时主动填写的，质量高得多。
     */
    private static final Set<String> ALLOWED_ACTIONS = Set.of("liked");

    /** 同一条推荐消息里的同一策略只保留最新一次反馈。 */
    public void save(Connection connection, UUID messageId, UUID userId, String strategyId, String action)
            throws SQLException {
        if (!ALLOWED_ACTIONS.contains(action)) {
            throw new IllegalArgumentException("不支持的反馈类型：" + action);
        }
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId 不能为空");
        }
        String sql = """
                INSERT INTO recommendation_feedback (message_id, user_id, strategy_id, action)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (message_id, strategy_id)
                    DO UPDATE SET action = EXCLUDED.action, updated_at = NOW()
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, userId);
            statement.setString(3, strategyId.trim());
            statement.setString(4, action);
            statement.executeUpdate();
        }
    }

    /**
     * 读取该用户点过赞的策略并按消息分组，用于恢复界面上的点赞状态。
     * 没有这个查询，用户刷新页面后就看不出自己点过哪些，会重复点击。
     */
    public Map<UUID, Set<String>> findLikesByUser(Connection connection, UUID userId) throws SQLException {
        String sql = """
                SELECT message_id, strategy_id FROM recommendation_feedback
                WHERE user_id = ? AND action = 'liked'
                """;
        Map<UUID, Set<String>> likes = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    likes.computeIfAbsent(result.getObject("message_id", UUID.class), key -> new HashSet<>())
                            .add(result.getString("strategy_id"));
                }
            }
        }
        return likes;
    }
}
