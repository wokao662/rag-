package com.example.rag.llm;

import com.example.rag.observability.TokenUsage;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * 读取 OpenAI 兼容的流式（SSE）聊天响应，把 delta 逐块拼成完整 content。
 *
 * <p>与一次性响应相比它多提供两样东西：每块到达时的累积文本（onProgress，供
 * {@link JsonFieldStreamExtractor} 提前抽出用户能看的字段），以及 finish_reason
 * （截断还是正常停止，是判断 max_tokens 该不该压的唯一证据）。
 *
 * <p>用量按块采集：SiliconFlow 的每个 chunk 都带 usage（生成中的渐进值，最后一块是终值），
 * 逐块覆盖即可拿到最终值，即使某些实现只在结尾带 usage，那次覆盖也正好落在最后。
 */
public final class ChatStreamReader {
    private ChatStreamReader() {
    }

    public static StreamResult read(Response response, Consumer<String> onProgress) throws IOException {
        ResponseBody body = response.body();
        if (body == null) throw new IOException("流式响应没有响应体");
        StringBuilder content = new StringBuilder();
        TokenUsage usage = null;
        String finishReason = null;
        try (BufferedSource source = body.source()) {
            String line;
            while ((line = source.readUtf8Line()) != null) {
                if (line.isEmpty() || line.startsWith(":")) continue;
                if (!line.startsWith("data:")) continue;
                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) break;
                JsonObject chunk;
                try {
                    chunk = JsonParser.parseString(data).getAsJsonObject();
                } catch (RuntimeException unparsable) {
                    // 单块无法解析就跳过：偶发脏块不该把整条流判死，完整性最终由整体解析兜底。
                    continue;
                }
                TokenUsage chunkUsage = TokenUsage.fromApi(chunk);
                if (chunkUsage != null) usage = chunkUsage;
                JsonElement choicesElement = chunk.get("choices");
                if (choicesElement == null || !choicesElement.isJsonArray()
                        || choicesElement.getAsJsonArray().isEmpty()) {
                    continue;
                }
                JsonObject choice = choicesElement.getAsJsonArray().get(0).getAsJsonObject();
                JsonElement finish = choice.get("finish_reason");
                if (finish != null && !finish.isJsonNull()) finishReason = finish.getAsString();
                JsonElement deltaElement = choice.get("delta");
                if (deltaElement == null || !deltaElement.isJsonObject()) continue;
                JsonElement piece = deltaElement.getAsJsonObject().get("content");
                // content 在首个角色块与 finish_reason=stop 的收尾块里是 null 或空串，不是增量。
                if (piece == null || piece.isJsonNull()) continue;
                String text = piece.getAsString();
                if (text.isEmpty()) continue;
                content.append(text);
                onProgress.accept(content.toString());
            }
        }
        return new StreamResult(content.toString(), usage, finishReason);
    }

    /** @param finishReason 可能为 null（模型/网关没给收尾块时），调用方按"未知"处理。 */
    public record StreamResult(String content, TokenUsage usage, String finishReason) {
    }
}
