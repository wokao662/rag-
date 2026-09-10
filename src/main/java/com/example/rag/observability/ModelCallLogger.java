package com.example.rag.observability;

import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 记录每次模型调用的输入输出、耗时和结果；日志写入失败不影响主流程。
 *
 * <p>这个类同时是训练数据的两道隐私闸：写入前脱敏、到期删除。两道都放在这里而不是各个调用方，
 * 是因为调用方会不断增加，而"记得脱敏"这种靠自觉的约定一定会被某个人忘掉——
 * 一旦有一次没脱敏，那张表就不再是"已脱敏的数据"，整个承诺作废。
 *
 * <p>另一件事：{@code user_id} 的外键是 {@code ON DELETE SET NULL}，所以删用户会自动把
 * 他的日志匿名化。留存下来的样本仍然有训练价值，但已经无法回溯到具体的人。
 */
@Service
public class ModelCallLogger {
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final int retentionDays;
    private final ModelCallLogRepository logs = new ModelCallLogRepository();

    /**
     * @param retentionDays 日志保留天数；小于等于 0 表示不清理，只建议本地排查问题时临时这么设
     */
    public ModelCallLogger(DataSource dataSource, TransactionTemplate transactions,
                           @Value("${app.logs.retention-days:365}") int retentionDays) {
        this.dataSource = dataSource;
        this.transactions = transactions;
        this.retentionDays = retentionDays;
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
        // 脱敏后的对象是深拷贝：调用方在记完日志之后还要继续用原始的 input/output，
        // 原地修改会把占位符带进画像抽取和推荐结果里。
        JsonObject safeInput = ModelCallSanitizer.scrub(input);
        JsonObject safeOutput = ModelCallSanitizer.scrub(output);
        String safeError = ModelCallSanitizer.scrubText(errorMessage);
        try {
            transactions.execute(transactionStatus -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                try {
                    logs.save(connection, userId, conversationId, taskType, model,
                            safeInput, safeOutput, latencyMs, status, safeError);
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

    /** 启动后清一次：部署即生效，不必等到第二天凌晨才兑现保留期承诺。 */
    @EventListener(ApplicationReadyEvent.class)
    public void purgeOnStartup() {
        purgeExpired();
    }

    /** 定期清理。默认每天 03:30，避开使用高峰。 */
    @Scheduled(cron = "${app.logs.purge-cron:0 30 3 * * *}")
    public void purgeExpired() {
        if (retentionDays <= 0) {
            System.err.println("模型调用日志保留期设为 " + retentionDays + "，已关闭自动清理");
            return;
        }
        try {
            Integer deleted = transactions.execute(transactionStatus -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                try {
                    return logs.deleteOlderThan(connection, retentionDays);
                } catch (SQLException error) {
                    throw new DataAccessResourceFailureException("清理模型调用日志失败", error);
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
            });
            if (deleted != null && deleted > 0) {
                System.out.println("已清理超过 " + retentionDays + " 天的模型调用日志 " + deleted + " 条");
            }
        } catch (RuntimeException error) {
            System.err.println("清理模型调用日志失败，将在下次调度重试：" + error.getMessage());
        }
    }
}
