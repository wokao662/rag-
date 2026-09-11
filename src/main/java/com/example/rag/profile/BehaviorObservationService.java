package com.example.rag.profile;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * 行为观测的采集入口。
 *
 * <p>采集与使用解耦：这里算出来的数字<b>不会</b>进入模型一的输入，也不会写进 model_call_logs。
 * v1 的模型一只看对话相关内容（当前消息、历史画像、过往询问策略及结果），这是 2026-09-10
 * 定下的隐私范围。观测数据现在照采照存，目的只有一个：将来评估"自述失真觉察"到底做不做得起来时，
 * 手上有对照面可用，而不是等到那天才开始攒数据。
 *
 * <p>采集失败一律只打印不抛出（与 ModelCallLogger 同一原则）：它是旁路数据，
 * 任何情况下都不能让对话或反馈提交失败。
 */
@Service
public class BehaviorObservationService {
    private final DataSource dataSource;
    private final TransactionTemplate transactions;
    private final BehaviorObservationRepository observations = new BehaviorObservationRepository();

    public BehaviorObservationService(DataSource dataSource, TransactionTemplate transactions) {
        this.dataSource = dataSource;
        this.transactions = transactions;
    }

    /**
     * 重算并保存该用户的全部观测指标。
     *
     * <p>必须在写入行为的那个事务提交之后调用，否则读到的是提交前的旧数据，
     * 观测值会永远慢一轮。
     */
    public void refresh(UUID userId) {
        if (userId == null) return;
        try {
            transactions.execute(status -> {
                Connection connection = DataSourceUtils.getConnection(dataSource);
                try {
                    observations.save(connection, userId, observations.compute(connection, userId));
                    return null;
                } catch (SQLException error) {
                    throw new DataAccessResourceFailureException("写入行为观测失败", error);
                } finally {
                    DataSourceUtils.releaseConnection(connection, dataSource);
                }
            });
        } catch (RuntimeException error) {
            // 旁路数据失败一律吃掉，但要把整个栈留下：包层的 message 只有一句“写入行为观测失败”，
            // 真正的 SQL 错误在 cause 里，而这条路失败对外不会有任何其他迹象。
            System.err.println("行为观测采集失败，不影响主流程：" + error.getMessage());
            error.printStackTrace(System.err);
        }
    }
}
