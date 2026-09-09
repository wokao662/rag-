package com.example.rag.profile;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public final class UserProfileRepository {
    public record StoredProfile(
            UUID userId,
            JsonObject profile,
            int version,
            double completeness,
            OffsetDateTime updatedAt
    ) {
    }

    public Optional<StoredProfile> findByUserId(Connection connection, UUID userId) throws SQLException {
        String sql = """
                SELECT user_id, profile_json, profile_version, completeness, updated_at
                FROM user_profiles
                WHERE user_id = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return Optional.empty();
                return Optional.of(new StoredProfile(
                        result.getObject("user_id", UUID.class),
                        JsonParser.parseString(result.getString("profile_json")).getAsJsonObject(),
                        result.getInt("profile_version"),
                        result.getDouble("completeness"),
                        result.getObject("updated_at", OffsetDateTime.class)
                ));
            }
        }
    }

    public void save(Connection connection, UUID userId, JsonObject profile, double completeness)
            throws SQLException {
        if (profile == null) throw new IllegalArgumentException("profile 不能为空");
        if (completeness < 0 || completeness > 1) {
            throw new IllegalArgumentException("画像完整度必须在 0～1 之间");
        }
        String sql = """
                INSERT INTO user_profiles (user_id, profile_json, completeness)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id) DO UPDATE SET
                    profile_json = EXCLUDED.profile_json,
                    completeness = EXCLUDED.completeness,
                    profile_version = user_profiles.profile_version + 1
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            statement.setObject(2, profile.toString(), Types.OTHER);
            statement.setDouble(3, completeness);
            statement.executeUpdate();
        }
    }

    /** 使用画像版本做乐观锁，避免并发请求静默覆盖彼此的画像更新。 */
    public boolean saveIfVersion(
            Connection connection,
            UUID userId,
            JsonObject profile,
            double completeness,
            int expectedVersion
    ) throws SQLException {
        if (profile == null) throw new IllegalArgumentException("profile 不能为空");
        if (completeness < 0 || completeness > 1) {
            throw new IllegalArgumentException("画像完整度必须在 0～1 之间");
        }
        if (expectedVersion < 0) throw new IllegalArgumentException("expectedVersion 不能小于 0");

        if (expectedVersion == 0) {
            String insert = """
                    INSERT INTO user_profiles (user_id, profile_json, completeness)
                    VALUES (?, ?, ?)
                    ON CONFLICT (user_id) DO NOTHING
                    """;
            try (PreparedStatement statement = connection.prepareStatement(insert)) {
                statement.setObject(1, userId);
                statement.setObject(2, profile.toString(), Types.OTHER);
                statement.setDouble(3, completeness);
                return statement.executeUpdate() == 1;
            }
        }

        String update = """
                UPDATE user_profiles
                SET profile_json = ?, completeness = ?, profile_version = profile_version + 1
                WHERE user_id = ? AND profile_version = ?
                """;
        try (PreparedStatement statement = connection.prepareStatement(update)) {
            statement.setObject(1, profile.toString(), Types.OTHER);
            statement.setDouble(2, completeness);
            statement.setObject(3, userId);
            statement.setInt(4, expectedVersion);
            return statement.executeUpdate() == 1;
        }
    }
}
