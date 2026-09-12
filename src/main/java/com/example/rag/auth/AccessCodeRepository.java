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
     * 取有效访问码的角色与人名，码不存在或已停用时返回 empty。
     *
     * <p>返回 {@code Optional} 而不是直接返回 {@code "tester"} 作默认：“没有这个码”与
     * “这个码是 tester” 必须分开，前者要拒绝请求，后者要放行。在这里合一会让无效的码
     * 拿到一个合法角色，而调用方看不出区别。
     *
     * <p>一次查回角色与人名，而不是各查一趟：两者的 WHERE 条件相同，分开查就是多一次
     * 数据库往返，而且两次查询之间理论上可能读到不同的行。兑换只要角色，审核只要人名，
     * 但都从这一个入口取，免得同一条件在仓库里写两遍。
     */
    public Optional<Access> accessOf(Connection connection, String code) throws SQLException {
        String sql = "SELECT role, label FROM access_codes WHERE code = ? AND NOT revoked";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? Optional.of(new Access(result.getString(1), result.getString(2)))
                        : Optional.empty();
            }
        }
    }

    /**
     * 一张访问码的身份。
     *
     * @param role  决定这个码能做什么
     * @param label 人名，写进 {@code strategies.reviewed_by} 这类审计列。V4 里可空，
     *              但 V10 的约束保证 reviewer 码一定有值
     */
    public record Access(String role, String label) {
    }

    public void touchLastUsed(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE access_codes SET last_used_at = NOW() WHERE code = ?")) {
            statement.setString(1, code);
            statement.executeUpdate();
        }
    }
}
