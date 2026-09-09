package com.example.rag.recommendation;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

public final class FeedbackRepository {
    private static final Set<String> ALLOWED_ACTIONS = Set.of("adopted", "dismissed");

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
                ON CONFLICT (message_id, strategy_id) DO UPDATE SET action = EXCLUDED.action
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, userId);
            statement.setString(3, strategyId.trim());
            statement.setString(4, action);
            statement.executeUpdate();
        }
    }
}
