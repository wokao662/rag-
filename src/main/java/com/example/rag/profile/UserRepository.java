package com.example.rag.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public final class UserRepository {
    public UUID findOrCreateByExternalId(Connection connection, String externalId) throws SQLException {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId 不能为空");
        }
        String sql = """
                INSERT INTO users (external_id)
                VALUES (?)
                ON CONFLICT (external_id) DO UPDATE SET external_id = EXCLUDED.external_id
                RETURNING id
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, externalId.trim());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new SQLException("创建或查找用户后没有返回 id");
                return result.getObject("id", UUID.class);
            }
        }
    }

    public Optional<UUID> findByExternalId(Connection connection, String externalId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM users WHERE external_id = ?")) {
            statement.setString(1, externalId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(result.getObject("id", UUID.class))
                        : Optional.empty();
            }
        }
    }
}
