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
             ConversationalProfileAgent profileAgent = new ConversationalProfileAgent(
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
                ProfileReadinessPolicy.Decision fallbackDecision = readiness.evaluate(merged);
                ProfileDecisionValidator.Decision decision;
                try {
                    decision = profileAgent.decide(merged, recent);
                } catch (Exception agentError) {
                    System.err.println("画像 Agent 暂时不可用，已使用本地规则继续：" + agentError.getMessage());
                    decision = new ProfileDecisionValidator.Decision(
                            fallbackDecision.ready() ? "recommend" : "ask",
                            fallbackDecision.ready(), 0,
                            "画像 Agent 调用失败，使用本地兜底规则",
                            fallbackDecision.missingFields(), List.of(),
                            fallbackDecision.followUpQuestion() == null
                                    ? "" : fallbackDecision.followUpQuestion());
                }

                try (Connection connection = Database.getConnection()) {
                    connection.setAutoCommit(false);
                    profiles.save(connection, userId, merged, fallbackDecision.completeness());
                    if (!decision.ready()) {
                        JsonObject metadata = new JsonObject();
                        metadata.addProperty("messageType", "profile_question");
                        metadata.addProperty("decisionConfidence", decision.confidence());
                        metadata.addProperty("decisionReason", decision.reason());
                        messages.save(connection, conversationId, "assistant", decision.nextQuestion(), metadata);
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
                System.out.printf("ready=%s，completeness=%.2f，decisionConfidence=%.2f%n",
                        decision.ready(), fallbackDecision.completeness(), decision.confidence());
                System.out.println("画像 Agent 判断：" + decision.reason());
                if (!decision.conflicts().isEmpty()) {
                    System.out.println("需要确认的信息冲突：" + GSON.toJson(decision.conflicts()));
                }
                if (decision.ready()) {
                    System.out.println("画像已足够进行初步推荐，下一阶段可进入 Qdrant。\n");
                } else {
                    System.out.println("助手：" + decision.nextQuestion() + "\n");
                }
            }
        } catch (Exception error) {
            System.err.println("画像会话测试失败：" + error.getMessage());
            System.exit(1);
        }
    }
}
