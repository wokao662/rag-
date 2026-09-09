package com.example.rag.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class ConversationRepository {
    public UUID create(Connection connection, UUID userId) throws SQLException {
        String sql = "INSERT INTO conversations (user_id) VALUES (?) RETURNING id";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("创建会话后没有返回 id");
                return result.getObject("id", UUID.class);
            }
        }
    }

    public void updateStatus(Connection connection, UUID conversationId, String status) throws SQLException {
        if (!status.equals("active") && !status.equals("closed") && !status.equals("archived")) {
            throw new IllegalArgumentException("不支持的会话状态：" + status);
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE conversations SET status = ? WHERE id = ?")) {
            statement.setString(1, status);
            statement.setObject(2, conversationId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("找不到会话：" + conversationId);
            }
        }
    }

    public boolean belongsToUser(Connection connection, UUID conversationId, UUID userId) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM conversations WHERE id = ? AND user_id = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setObject(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
