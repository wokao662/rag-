import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.util.List;
import java.util.Scanner;
import java.util.UUID;

/** 持久化多轮画像测试。输入 exit 结束；用户、消息与画像会保存在 PostgreSQL。 */
public final class UserProfileConversationTest {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private UserProfileConversationTest() {
    }

    public static void main(String[] args) {
        String externalId = args.length == 0 ? "local-profile-test-user" : args[0].trim();
        UserRepository users = new UserRepository();
        ConversationRepository conversations = new ConversationRepository();
        MessageRepository messages = new MessageRepository();
        UserProfileRepository profiles = new UserProfileRepository();
        UserProfileValidator validator = new UserProfileValidator();
        UserProfileMerger merger = new UserProfileMerger();
        ProfileReadinessPolicy readiness = new ProfileReadinessPolicy();

        try (UserProfileExtractor extractor = new UserProfileExtractor(
                AppConfig.require("SILICONFLOW_API_KEY"));
             Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            UUID userId;
            UUID conversationId;
            try (Connection connection = Database.getConnection()) {
                connection.setAutoCommit(false);
                userId = users.findOrCreateByExternalId(connection, externalId);
                conversationId = conversations.create(connection, userId);
                connection.commit();
            }

            System.out.println("画像测试已开始。用户=" + externalId + "，会话=" + conversationId);
            System.out.println("输入 exit 结束。画像足够推荐时程序会显示 ready=true，但暂不调用 Qdrant。\n");

            while (true) {
                System.out.print("你：");
                if (!scanner.hasNextLine()) break;
                String input = scanner.nextLine().trim();
                if (input.equalsIgnoreCase("exit")) break;
                if (input.isBlank()) continue;

                UUID userMessageId;
                JsonObject existingProfile;
                List<MessageRepository.StoredMessage> recent;
                try (Connection connection = Database.getConnection()) {
                    connection.setAutoCommit(false);
                    userMessageId = messages.save(connection, conversationId, "user", input, new JsonObject());
                    existingProfile = profiles.findByUserId(connection, userId)
                            .map(UserProfileRepository.StoredProfile::profile).orElseGet(JsonObject::new);
                    recent = messages.findRecent(connection, conversationId, 8);
                    connection.commit();
                }

                JsonObject extraction = extractor.extract(existingProfile, recent, input);
                UserProfileValidator.ValidationResult validation = validator.validate(extraction, input);
                JsonObject merged = merger.merge(existingProfile, validation.acceptedUpdates(), userMessageId);
                ProfileReadinessPolicy.Decision decision = readiness.evaluate(merged);

                try (Connection connection = Database.getConnection()) {
                    connection.setAutoCommit(false);
                    profiles.save(connection, userId, merged, decision.completeness());
                    if (!decision.ready()) {
                        JsonObject metadata = new JsonObject();
                        metadata.addProperty("messageType", "profile_question");
                        messages.save(connection, conversationId, "assistant", decision.followUpQuestion(), metadata);
                    }
                    connection.commit();
                }

                System.out.println("\n通过校验的画像更新：");
                System.out.println(GSON.toJson(validation.acceptedUpdates()));
                if (!validation.rejections().isEmpty()) {
                    System.out.println("被Java拒绝的候选：" + GSON.toJson(validation.rejections()));
                }
                System.out.println("当前画像：");
                System.out.println(GSON.toJson(merged));
                System.out.printf("ready=%s，completeness=%.2f%n", decision.ready(), decision.completeness());
                if (decision.ready()) {
                    System.out.println("画像已足够进行初步推荐，下一阶段可进入 Qdrant。\n");
                } else {
                    System.out.println("助手：" + decision.followUpQuestion() + "\n");
                }
            }
        } catch (Exception error) {
            System.err.println("画像会话测试失败：" + error.getMessage());
            System.exit(1);
        }
    }
}
