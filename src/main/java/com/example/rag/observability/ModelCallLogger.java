package com.example.rag.observability;

import com.google.gson.JsonObject;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/** 记录每次模型调用的输入输出、耗时和结果；日志写入失败不影响主流程。 */
@Service
public class ModelCallLogger {
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final ModelCallLogRepository logs = new ModelCallLogRepository();

    public ModelCallLogger(DataSource dataSource, TransactionTemplate transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    public void log(
            UUID userId,
            UUID conversationId,
            String taskType,
            String model,
            JsonObject input,
            JsonObject output,
            long latencyMs,
            String status,
            String errorMessage
    ) {
        try {
            transactions.execute(transactionStatus -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                try {
                    logs.save(connection, userId, conversationId, taskType, model,
                            input, output, latencyMs, status, errorMessage);
                    return null;
                } catch (SQLException error) {
                    throw new DataAccessResourceFailureException("写入模型调用日志失败", error);
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
            });
        } catch (RuntimeException ignored) {
            // 日志是辅助设施，失败不应影响画像和推荐主流程。
        }
    }
}
