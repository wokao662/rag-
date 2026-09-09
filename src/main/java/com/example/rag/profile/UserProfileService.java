package com.example.rag.profile;

import com.example.rag.observability.ModelCallLogger;
import com.example.rag.recommendation.RecommendationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserProfileService {
    private static final int RECENT_MESSAGE_LIMIT = 12;
    private static final Gson GSON = new Gson();

    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final UserProfileExtractor extractor;
    private final ConversationalProfileAgent profileAgent;
    private final UserProfileValidator validator;
    private final UserProfileMerger merger;
    private final ProfileReadinessPolicy readinessPolicy;
    private final RecommendationService recommendationService;
    private final ModelCallLogger callLogger;
    private final UserRepository users = new UserRepository();
    private final ConversationRepository conversations = new ConversationRepository();
    private final MessageRepository messages = new MessageRepository();
    private final UserProfileRepository profiles = new UserProfileRepository();

    public UserProfileService(
            DataSource dataSource,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            UserProfileExtractor extractor,
            ConversationalProfileAgent profileAgent,
            UserProfileValidator validator,
            UserProfileMerger merger,
            ProfileReadinessPolicy readinessPolicy,
            RecommendationService recommendationService,
            ModelCallLogger callLogger
    ) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.extractor = extractor;
        this.profileAgent = profileAgent;
        this.validator = validator;
        this.merger = merger;
        this.readinessPolicy = readinessPolicy;
        this.recommendationService = recommendationService;
        this.callLogger = callLogger;
    }

    public ConversationStarted startConversation(String externalId) {
        String normalizedId = normalizeExternalId(externalId);
        return inTransaction(connection -> {
            UUID userId = users.findOrCreateByExternalId(connection, normalizedId);
            UUID conversationId = conversations.create(connection, userId);
            return new ConversationStarted(userId, conversationId);
        });
    }

    public TurnResult processMessage(String externalId, UUID conversationId, String content) {
        String normalizedId = normalizeExternalId(externalId);
        if (content == null || content.isBlank()) throw new IllegalArgumentException("消息内容不能为空");

        TurnContext context = inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            requireConversationOwnership(connection, conversationId, userId);
            UUID messageId = messages.save(connection, conversationId, "user", content, new JsonObject());
            Optional<UserProfileRepository.StoredProfile> stored = profiles.findByUserId(connection, userId);
            JsonObject profile = stored.map(UserProfileRepository.StoredProfile::profile)
                    .orElseGet(JsonObject::new);
            int version = stored.map(UserProfileRepository.StoredProfile::version).orElse(0);
            List<MessageRepository.StoredMessage> recent =
                    messages.findRecent(connection, conversationId, RECENT_MESSAGE_LIMIT);
            return new TurnContext(userId, messageId, profile, version, recent);
        });

        JsonObject extraction;
        JsonObject extractInput = new JsonObject();
        extractInput.addProperty("currentUserMessage", content.trim());
        long extractStart = System.currentTimeMillis();
        try {
            extraction = extractor.extract(context.profile(), context.recentMessages(), content.trim());
            callLogger.log(context.userId(), conversationId, "extract", UserProfileExtractor.MODEL,
                    extractInput, extraction, System.currentTimeMillis() - extractStart, "success", null);
        } catch (IOException error) {
            callLogger.log(context.userId(), conversationId, "extract", UserProfileExtractor.MODEL,
                    extractInput, null, System.currentTimeMillis() - extractStart, "failed", error.getMessage());
            throw new ProfileModelException("画像抽取服务暂时不可用", error);
        }
        UserProfileValidator.ValidationResult validation = validator.validate(extraction, content.trim());
        JsonObject merged = merger.merge(context.profile(), validation.acceptedUpdates(), context.messageId());
        ProfileReadinessPolicy.Decision fallback = readinessPolicy.evaluate(merged);
        ProfileDecisionValidator.Decision decision = decideWithFallback(
                merged, context.recentMessages(), fallback, context.userId(), conversationId);

        RecommendationService.RecommendationResult recommendation = null;
        if (decision.ready()) {
            try {
                recommendation = recommendationService.recommend(merged, context.userId(), conversationId);
            } catch (RecommendationService.RecommendationUnavailableException ignored) {
                // 推荐失败不阻断画像流程，前端可稍后通过推荐接口重试。
            }
        }

        RecommendationService.RecommendationResult finalRecommendation = recommendation;
        UUID[] assistantMessageId = new UUID[1];
        inTransaction(connection -> {
            boolean saved = profiles.saveIfVersion(
                    connection, context.userId(), merged, fallback.completeness(), context.profileVersion());
            if (!saved) throw new ProfileConflictException("画像已被另一条请求更新，请重试当前消息");
            if (!decision.ready()) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("messageType", "profile_question");
                metadata.addProperty("decisionConfidence", decision.confidence());
                metadata.addProperty("decisionReason", decision.reason());
                assistantMessageId[0] = messages.save(
                        connection, conversationId, "assistant", decision.nextQuestion(), metadata);
            } else if (finalRecommendation != null) {
                JsonObject metadata = new JsonObject();
                metadata.addProperty("messageType", "recommendation");
                metadata.addProperty("recommendationStatus", finalRecommendation.status());
                metadata.add("recommendation", JsonParser.parseString(GSON.toJson(finalRecommendation)));
                assistantMessageId[0] = messages.save(
                        connection, conversationId, "assistant", finalRecommendation.answer(), metadata);
            }
            return null;
        });

        return new TurnResult(
                decision.action(), decision.ready(), fallback.completeness(), decision.confidence(),
                decision.reason(), decision.nextQuestion(), decision.missingInformation(),
                decision.conflicts(), toMap(merged), toMap(validation.acceptedUpdates()),
                validation.rejections().size(), finalRecommendation, assistantMessageId[0]);
    }

    public ProfileResult getProfile(String externalId) {
        String normalizedId = normalizeExternalId(externalId);
        return inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            return profiles.findByUserId(connection, userId)
                    .map(profile -> new ProfileResult(
                            profile.userId(), toMap(profile.profile()), profile.version(),
                            profile.completeness(), profile.updatedAt().toString()))
                    .orElseThrow(() -> new ProfileNotFoundException("该用户还没有画像"));
        });
    }

    public List<ConversationSummary> listConversations(String externalId) {
        String normalizedId = normalizeExternalId(externalId);
        return inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            return conversations.findByUserId(connection, userId, 100).stream()
                    .map(conversation -> new ConversationSummary(
                            conversation.id(), conversationTitle(conversation), conversation.status(),
                            conversation.createdAt().toString(), conversation.updatedAt().toString()))
                    .toList();
        });
    }

    public List<MessageView> getMessages(String externalId, UUID conversationId) {
        String normalizedId = normalizeExternalId(externalId);
        return inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            requireConversationOwnership(connection, conversationId, userId);
            return messages.findRecent(connection, conversationId, 100).stream()
                    .map(message -> new MessageView(
                            message.id(), message.role(), message.content(),
                            toMap(message.metadata()), message.createdAt().toString()))
                    .toList();
        });
    }

    public RecommendationService.RecommendationResult recommend(String externalId) {
        String normalizedId = normalizeExternalId(externalId);
        UUID[] userHolder = new UUID[1];
        JsonObject profile = inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            userHolder[0] = userId;
            return profiles.findByUserId(connection, userId)
                    .map(UserProfileRepository.StoredProfile::profile)
                    .orElseThrow(() -> new ProfileNotFoundException("该用户还没有画像"));
        });
        return recommendationService.recommend(profile, userHolder[0], null);
    }

    private static final int CONVERSATION_TITLE_LENGTH = 30;

    private static String conversationTitle(ConversationRepository.StoredConversation conversation) {
        String firstMessage = conversation.firstUserMessage();
        if (firstMessage == null || firstMessage.isBlank()) return "新会话";
        String title = firstMessage.trim().replaceAll("\\s+", " ");
        return title.length() <= CONVERSATION_TITLE_LENGTH
                ? title : title.substring(0, CONVERSATION_TITLE_LENGTH) + "…";
    }

    private ProfileDecisionValidator.Decision decideWithFallback(
            JsonObject profile,
            List<MessageRepository.StoredMessage> recent,
            ProfileReadinessPolicy.Decision fallback,
            UUID userId,
            UUID conversationId
    ) {
        long start = System.currentTimeMillis();
        try {
            ProfileDecisionValidator.Decision decision = profileAgent.decide(profile, recent);
            JsonObject output = new JsonObject();
            output.addProperty("action", decision.action());
            output.addProperty("ready", decision.ready());
            output.addProperty("confidence", decision.confidence());
            output.addProperty("reason", decision.reason());
            callLogger.log(userId, conversationId, "decide", ConversationalProfileAgent.MODEL,
                    null, output, System.currentTimeMillis() - start, "success", null);
            return decision;
        } catch (Exception error) {
            callLogger.log(userId, conversationId, "decide", ConversationalProfileAgent.MODEL,
                    null, null, System.currentTimeMillis() - start, "fallback", error.getMessage());
            return new ProfileDecisionValidator.Decision(
                    fallback.ready() ? "recommend" : "ask", fallback.ready(), 0,
                    "画像 Agent 调用失败，使用本地兜底规则", fallback.missingFields(), List.of(),
                    fallback.followUpQuestion() == null ? "" : fallback.followUpQuestion());
        }
    }

    private UUID requireUser(Connection connection, String externalId) throws SQLException {
        return users.findByExternalId(connection, externalId)
                .orElseThrow(() -> new ProfileNotFoundException("找不到用户：" + externalId));
    }

    private void requireConversationOwnership(Connection connection, UUID conversationId, UUID userId)
            throws SQLException {
        if (!conversations.belongsToUser(connection, conversationId, userId)) {
            throw new ConversationNotFoundException("找不到属于该用户的会话");
        }
    }

    private String normalizeExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId 不能为空");
        }
        String normalized = externalId.trim();
        if (normalized.length() > 128 || !normalized.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("externalId 只能包含字母、数字、点、下划线和连字符，最长 128 位");
        }
        return normalized;
    }

    private Map<String, Object> toMap(JsonObject object) {
        try {
            return objectMapper.readValue(object.toString(), new TypeReference<>() { });
        } catch (IOException error) {
            throw new IllegalStateException("画像 JSON 转换失败", error);
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        return transactions.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                return work.execute(connection);
            } catch (SQLException error) {
                throw new DataAccessResourceFailureException("数据库操作失败", error);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    @FunctionalInterface
    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private record TurnContext(
            UUID userId,
            UUID messageId,
            JsonObject profile,
            int profileVersion,
            List<MessageRepository.StoredMessage> recentMessages
    ) {
    }

    public record ConversationStarted(UUID userId, UUID conversationId) {
    }

    public record ConversationSummary(
            UUID conversationId,
            String title,
            String status,
            String createdAt,
            String updatedAt
    ) {
    }

    public record MessageView(
            UUID messageId,
            String role,
            String content,
            Map<String, Object> metadata,
            String createdAt
    ) {
    }

    public record TurnResult(
            String action,
            boolean ready,
            double completeness,
            double decisionConfidence,
            String decisionReason,
            String assistantMessage,
            List<String> missingInformation,
            List<String> conflicts,
            Map<String, Object> profile,
            Map<String, Object> acceptedUpdates,
            int rejectedUpdateCount,
            RecommendationService.RecommendationResult recommendation,
            UUID assistantMessageId
    ) {
    }

    public record ProfileResult(
            UUID userId,
            Map<String, Object> profile,
            int version,
            double completeness,
            String updatedAt
    ) {
    }

    public static final class ProfileNotFoundException extends RuntimeException {
        public ProfileNotFoundException(String message) { super(message); }
    }

    public static final class ConversationNotFoundException extends RuntimeException {
        public ConversationNotFoundException(String message) { super(message); }
    }

    public static final class ProfileConflictException extends RuntimeException {
        public ProfileConflictException(String message) { super(message); }
    }

    public static final class ProfileModelException extends RuntimeException {
        public ProfileModelException(String message, Throwable cause) { super(message, cause); }
    }
}
