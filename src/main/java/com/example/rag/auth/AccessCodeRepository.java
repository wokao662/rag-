package com.example.rag.auth;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

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

    /**
     * 取有效访问码的角色，码不存在或已停用时返回 empty。
     *
     * <p>返回 {@code Optional} 而不是直接返回 {@code "tester"} 作默认：“没有这个码”与
     * “这个码是 tester” 必须分开，前者要拒绝请求，后者要放行。在这里合一会让无效的码
     * 拿到一个合法角色，而调用方看不出区别。
     *
     * <p>WHERE 条件与 {@link #isActive} 一致，所以一次查询同时完成“是否有效”与“什么角色”
     * 两件事，兑换时不必再多跑一趟数据库。
     */
    public Optional<String> roleOf(Connection connection, String code) throws SQLException {
        String sql = "SELECT role FROM access_codes WHERE code = ? AND NOT revoked";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.ofNullable(result.getString(1)) : Optional.empty();
            }
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
