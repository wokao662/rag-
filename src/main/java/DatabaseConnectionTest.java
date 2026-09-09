import com.google.gson.JsonObject;
import com.example.rag.profile.ConversationRepository;
import com.example.rag.profile.MessageRepository;
import com.example.rag.profile.UserProfileRepository;
import com.example.rag.profile.UserRepository;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** 验证 JDBC 和四个 Repository；所有测试写入最终都会回滚。 */
public final class DatabaseConnectionTest {
    private static final Set<String> REQUIRED_TABLES =
            Set.of("users", "conversations", "messages", "user_profiles");

    private DatabaseConnectionTest() {
    }

    public static void main(String[] args) {
        try (Connection connection = Database.getConnection()) {
            connection.setAutoCommit(false);
            try {
                System.out.println("PostgreSQL 连接成功：" + databaseVersion(connection));
                verifyTables(connection);

                UserRepository users = new UserRepository();
                ConversationRepository conversations = new ConversationRepository();
                MessageRepository messages = new MessageRepository();
                UserProfileRepository profiles = new UserProfileRepository();

                UUID userId = users.findOrCreateByExternalId(connection, "database-test-" + UUID.randomUUID());
                UUID conversationId = conversations.create(connection, userId);
                JsonObject messageMetadata = new JsonObject();
                messageMetadata.addProperty("test", true);
                UUID messageId = messages.save(
                        connection, conversationId, "user", "我背单词很快忘，每天能学习30分钟。", messageMetadata);

                JsonObject profile = new JsonObject();
                profile.addProperty("mainDifficulty", "容易遗忘");
                profile.addProperty("availableMinutesPerDay", 30);
                profiles.save(connection, userId, profile, 0.5);

                List<MessageRepository.StoredMessage> recent = messages.findRecent(connection, conversationId, 8);
                UserProfileRepository.StoredProfile storedProfile = profiles.findByUserId(connection, userId)
                        .orElseThrow(() -> new IllegalStateException("画像写入后无法读取"));
                if (recent.size() != 1 || !recent.get(0).id().equals(messageId)) {
                    throw new IllegalStateException("消息写入或读取结果不一致");
                }
                if (storedProfile.profile().get("availableMinutesPerDay").getAsInt() != 30) {
                    throw new IllegalStateException("JSONB 画像写入或读取结果不一致");
                }

                System.out.println("用户、会话、消息和 JSONB 画像读写成功。");
                System.out.println("测试事务即将回滚，不会留下测试数据。");
            } finally {
                connection.rollback();
            }
        } catch (Exception error) {
            System.err.println("数据库测试失败：" + error.getMessage());
            System.exit(1);
        }
    }

    private static String databaseVersion(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT version()")) {
            result.next();
            return result.getString(1);
        }
    }

    private static void verifyTables(Connection connection) throws Exception {
        Set<String> tables;
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT tablename FROM pg_tables WHERE schemaname = 'public'")) {
            java.util.ArrayList<String> names = new java.util.ArrayList<>();
            while (result.next()) names.add(result.getString(1));
            tables = names.stream().collect(Collectors.toSet());
        }
        if (!tables.containsAll(REQUIRED_TABLES)) {
            throw new IllegalStateException("缺少数据库表：" + REQUIRED_TABLES.stream()
                    .filter(table -> !tables.contains(table)).toList());
        }
        System.out.println("数据库表检查通过：" + REQUIRED_TABLES);
    }
}
