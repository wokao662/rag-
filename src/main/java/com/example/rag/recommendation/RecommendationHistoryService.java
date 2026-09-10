package com.example.rag.recommendation;

import com.example.rag.profile.MessageRepository;
import com.example.rag.profile.UserRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 推荐历史与尝试后反馈。
 *
 * <p>历史推荐不需要新表：保存推荐消息时已把完整的 RecommendationResult 序列化进
 * messages.metadata_json，这里直接读那份快照。快照的好处是即使某个方法后来被降权归档，
 * 用户仍然看得到当初推荐给他的是什么，历史页不会出现空白卡片。
 *
 * <p>接口返回按时间流水的结构，「按方法聚合」视图由前端分组完成——一个用户对一个方法
 * 只有一条反馈，聚合只是展示方式，不是数据模型。
 */
@Service
public class RecommendationHistoryService {
    private static final int HISTORY_LIMIT = 200;

    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final StrategyGovernanceService governance;
    private final UserRepository users = new UserRepository();
    private final MessageRepository messages = new MessageRepository();
    private final FeedbackRepository likes = new FeedbackRepository();
    private final TrialFeedbackRepository trials = new TrialFeedbackRepository();

    public RecommendationHistoryService(DataSource dataSource, TransactionTemplate transactions,
                                        StrategyGovernanceService governance) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.governance = governance;
    }

    /** 读取该用户历史上收到过的全部推荐，并带上点赞与尝试后反馈的当前状态。 */
    public List<RecommendationEvent> history(String externalId) {
        String normalizedId = requireExternalId(externalId);
        return inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            List<MessageRepository.StoredMessage> stored =
                    messages.findRecommendationsByUser(connection, userId, HISTORY_LIMIT);
            Map<UUID, Set<String>> likedByMessage = likes.findLikesByUser(connection, userId);
            Map<String, TrialFeedbackRepository.TrialFeedback> trialByStrategy =
                    trials.findByUser(connection, userId);

            List<RecommendationEvent> events = new ArrayList<>();
            for (MessageRepository.StoredMessage message : stored) {
                JsonObject recommendation = asObject(message.metadata().get("recommendation"));
                if (recommendation == null) continue;
                Set<String> likedStrategies = likedByMessage.getOrDefault(message.id(), Set.of());

                List<RecommendedMethod> methods = new ArrayList<>();
                JsonArray array = asArray(recommendation.get("recommendations"));
                if (array != null) {
                    for (JsonElement element : array) {
                        JsonObject strategy = asObject(element);
                        if (strategy == null) continue;
                        String strategyId = text(strategy, "strategyId");
                        if (strategyId == null || strategyId.isBlank()) continue;
                        methods.add(new RecommendedMethod(
                                strategyId,
                                text(strategy, "strategyName"),
                                text(strategy, "reason"),
                                textList(strategy, "methodSteps"),
                                textList(strategy, "sourceIds"),
                                textList(strategy, "caveats"),
                                likedStrategies.contains(strategyId),
                                toTrialState(trialByStrategy.get(strategyId))
                        ));
                    }
                }
                events.add(new RecommendationEvent(
                        message.id(),
                        message.conversationId(),
                        message.createdAt().toString(),
                        text(recommendation, "status"),
                        message.content(),
                        List.copyOf(methods)
                ));
            }
            return List.copyOf(events);
        });
    }

    /** 提交或修改对某个方法的尝试后反馈。 */
    public TrialState submitTrialFeedback(String externalId, String strategyId, TrialSubmission submission) {
        String normalizedId = requireExternalId(externalId);
        if (strategyId == null || strategyId.isBlank()) {
            throw new IllegalArgumentException("strategyId 不能为空");
        }
        if (submission == null) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }
        String normalizedStrategyId = strategyId.trim();
        return inTransaction(connection -> {
            UUID userId = requireUser(connection, normalizedId);
            // 只能对系统真的推荐过的方法反馈。少了这一步，任何人都能凭空对任意 strategyId
            // 提交反馈，直接污染驱动渐进投放升降权的统计数据。
            if (!messages.wasRecommendedTo(connection, userId, normalizedStrategyId)) {
                throw new StrategyNotRecommendedException(
                        "这个方法没有推荐给你过，无法提交反馈：" + normalizedStrategyId);
            }
            UUID sourceMessageId = submission.sourceMessageId();
            if (sourceMessageId != null && !messages.belongsToUser(connection, sourceMessageId, userId)) {
                throw new StrategyNotRecommendedException("找不到属于该用户的推荐消息");
            }
            TrialFeedbackRepository.TrialFeedback saved = trials.save(
                    connection, userId, normalizedStrategyId, submission.tried(),
                    submission.outcome(), submission.note(), sourceMessageId);
            // 同一事务内重算，保证反馈与分数不会处于“已写入但未生效”的中间态：
            // 分数是派生值，跟反馈一起提交或一起回滚。TransactionTemplate 默认 REQUIRED，
            // 这里会加入当前事务而不是新开一个。
            governance.recalculate(normalizedStrategyId);
            return toTrialState(saved);
        });
    }

    private static TrialState toTrialState(TrialFeedbackRepository.TrialFeedback trial) {
        if (trial == null) return null;
        return new TrialState(
                trial.tried(), trial.outcome(), trial.note(),
                trial.updatedAt() == null ? null : trial.updatedAt().toString());
    }

    private UUID requireUser(Connection connection, String externalId) throws SQLException {
        return users.findByExternalId(connection, externalId)
                .orElseThrow(() -> new StrategyNotRecommendedException("找不到用户：" + externalId));
    }

    private static String requireExternalId(String externalId) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId 不能为空");
        }
        return externalId.trim();
    }

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static JsonArray asArray(JsonElement element) {
        return element != null && element.isJsonArray() ? element.getAsJsonArray() : null;
    }

    private static String text(JsonObject object, String field) {
        JsonElement element = object.get(field);
        return element == null || element.isJsonNull() ? null : element.getAsString();
    }

    private static List<String> textList(JsonObject object, String field) {
        JsonArray array = asArray(object.get(field));
        if (array == null) return List.of();
        List<String> values = new ArrayList<>();
        for (JsonElement element : array) {
            if (element != null && !element.isJsonNull()) values.add(element.getAsString());
        }
        return List.copyOf(values);
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

    /** 一次推荐事件，methods 是那次同时推荐出的全部方法。 */
    public record RecommendationEvent(
            UUID messageId,
            UUID conversationId,
            String recommendedAt,
            String status,
            String answer,
            List<RecommendedMethod> methods
    ) {
    }

    /** 历史页展示的一条方法，内容取自推荐当时的快照。 */
    public record RecommendedMethod(
            String strategyId,
            String strategyName,
            String reason,
            List<String> methodSteps,
            List<String> sourceIds,
            List<String> caveats,
            boolean liked,
            TrialState trial
    ) {
    }

    /** 尝试后反馈的当前状态，null 表示还没填过。 */
    public record TrialState(
            boolean tried,
            String outcome,
            String note,
            String updatedAt
    ) {
    }

    public record TrialSubmission(
            boolean tried,
            String outcome,
            String note,
            UUID sourceMessageId
    ) {
    }

    public static final class StrategyNotRecommendedException extends RuntimeException {
        public StrategyNotRecommendedException(String message) {
            super(message);
        }
    }
}
