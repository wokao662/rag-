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
}
