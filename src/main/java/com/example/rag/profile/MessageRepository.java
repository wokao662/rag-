package com.example.rag.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public final class MessageRepository {
    public record StoredMessage(
            UUID id,
            UUID conversationId,
            String role,
            String content,
            JsonObject metadata,
            OffsetDateTime createdAt
    ) {
    }

    public UUID save(
            Connection connection,
            UUID conversationId,
            String role,
            String content,
            JsonObject metadata
    ) throws SQLException {
        if (!role.equals("user") && !role.equals("assistant") && !role.equals("system")) {
            throw new IllegalArgumentException("不支持的消息角色：" + role);
        }
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("消息内容不能为空");
        }
        String sql = """
                INSERT INTO messages (conversation_id, role, content, metadata_json)
                VALUES (?, ?, ?, ?)
                RETURNING id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setString(2, role);
            statement.setString(3, content.trim());
            statement.setObject(4, metadata == null ? "{}" : metadata.toString(), Types.OTHER);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("保存消息后没有返回 id");
                return result.getObject("id", UUID.class);
            }
        }
    }

    /** 判断消息是否属于该用户的会话，用于反馈等跨表操作前的归属校验。 */
    public boolean belongsToUser(Connection connection, UUID messageId, UUID userId) throws SQLException {
        String sql = """
                SELECT EXISTS (
                    SELECT 1 FROM messages m
                    JOIN conversations c ON m.conversation_id = c.id
                    WHERE m.id = ? AND c.user_id = ?
                )
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, messageId);
            statement.setObject(2, userId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    public List<StoredMessage> findRecent(Connection connection, UUID conversationId, int limit)
            throws SQLException {
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("消息读取数量必须在 1～100 之间");
        }
        String sql = """
                SELECT id, conversation_id, role, content, metadata_json, created_at
                FROM messages
                WHERE conversation_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """;
        List<StoredMessage> messages = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, conversationId);
            statement.setInt(2, limit);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    messages.add(new StoredMessage(
                            result.getObject("id", UUID.class),
                            result.getObject("conversation_id", UUID.class),
                            result.getString("role"),
                            result.getString("content"),
                            JsonParser.parseString(result.getString("metadata_json")).getAsJsonObject(),
                            result.getObject("created_at", OffsetDateTime.class)
                    ));
                }
            }
        }
        Collections.reverse(messages);
        return messages;
    }
}
