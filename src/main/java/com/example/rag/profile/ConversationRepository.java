package com.example.rag.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ConversationRepository {
    public record StoredConversation(
            UUID id,
            UUID userId,
            String status,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
    }

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

    public List<StoredConversation> findByUserId(Connection connection, UUID userId, int limit)
            throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("会话读取数量必须在 1～100 之间");
        }
        String sql = """
                SELECT id, user_id, status, created_at, updated_at
                FROM conversations
                WHERE user_id = ?
                ORDER BY updated_at DESC
                LIMIT ?
                """;
        List<StoredConversation> conversations = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    conversations.add(new StoredConversation(
                            result.getObject("id", UUID.class),
                            result.getObject("user_id", UUID.class),
                            result.getString("status"),
                            result.getObject("created_at", OffsetDateTime.class),
                            result.getObject("updated_at", OffsetDateTime.class)
                    ));
                }
            }
        }
        return conversations;
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
