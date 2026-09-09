package com.example.rag.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public final class AccessCodeRepository {
    public boolean isActive(Connection connection, String code) throws SQLException {
        String sql = "SELECT EXISTS (SELECT 1 FROM access_codes WHERE code = ? AND NOT revoked)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** 一张访问码都没有时视为开发模式，不启用访问控制。 */
    public boolean anyExists(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT EXISTS (SELECT 1 FROM access_codes)");
             ResultSet result = statement.executeQuery()) {
            return result.next() && result.getBoolean(1);
        }
    }

    public void touchLastUsed(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE access_codes SET last_used_at = NOW() WHERE code = ?")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }
}
