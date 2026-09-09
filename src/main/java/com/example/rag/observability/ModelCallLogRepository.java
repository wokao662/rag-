package com.example.rag.observability;

import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Set;
import java.util.UUID;

public final class ModelCallLogRepository {
    private static final Set<String> ALLOWED_TASKS = Set.of("extract", "decide", "recommend");
    private static final Set<String> ALLOWED_STATUS = Set.of("success", "fallback", "failed");

    public void save(
            Connection connection,
            UUID userId,
            UUID conversationId,
            String taskType,
            String model,
            JsonObject input,
            JsonObject output,
            long latencyMs,
            String status,
            String errorMessage
    ) throws SQLException {
        if (!ALLOWED_TASKS.contains(taskType)) {
            throw new IllegalArgumentException("不支持的任务类型：" + taskType);
        }
        if (!ALLOWED_STATUS.contains(status)) {
            throw new IllegalArgumentException("不支持的状态：" + status);
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model 不能为空");
        }
        if (latencyMs < 0) throw new IllegalArgumentException("latencyMs 不能小于 0");

        String sql = """
                INSERT INTO model_call_logs
                    (user_id, conversation_id, task_type, model, input_json, output_json,
                     latency_ms, status, error_message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, userId);
            statement.setObject(2, conversationId);
            statement.setString(3, taskType);
            statement.setString(4, model.trim());
            statement.setObject(5, input == null ? "{}" : input.toString(), Types.OTHER);
            if (output == null) {
                statement.setNull(6, Types.OTHER);
            } else {
                statement.setObject(6, output.toString(), Types.OTHER);
            }
            statement.setLong(7, latencyMs);
            statement.setString(8, status);
            statement.setString(9, errorMessage);
            statement.executeUpdate();
        }
    }
}
