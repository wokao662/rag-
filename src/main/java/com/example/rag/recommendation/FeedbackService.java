package com.example.rag.recommendation;

import com.example.rag.profile.MessageRepository;
import com.example.rag.profile.UserRepository;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** 记录用户对推荐策略的反馈（采纳或不感兴趣），作为后续推荐效果评估的数据来源。 */
@Service
public class FeedbackService {
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final UserRepository users = new UserRepository();
    private final MessageRepository messages = new MessageRepository();
    private final FeedbackRepository feedback = new FeedbackRepository();

    public FeedbackService(DataSource dataSource, TransactionTemplate transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    public FeedbackResult record(String externalId, UUID messageId, String strategyId, String action) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("externalId 不能为空");
        }
        return inTransaction(connection -> {
            UUID userId = users.findByExternalId(connection, externalId.trim())
                    .orElseThrow(() -> new FeedbackNotFoundException("找不到用户：" + externalId));
            if (!messages.belongsToUser(connection, messageId, userId)) {
                throw new FeedbackNotFoundException("找不到属于该用户的消息");
            }
            feedback.save(connection, messageId, userId, strategyId, action);
            return new FeedbackResult(messageId, strategyId.trim(), action);
        });
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

    public record FeedbackResult(UUID messageId, String strategyId, String action) {
    }

    public static final class FeedbackNotFoundException extends RuntimeException {
        public FeedbackNotFoundException(String message) { super(message); }
    }
}
