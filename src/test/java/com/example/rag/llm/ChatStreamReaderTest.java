package com.example.rag.llm;

import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 流式响应读取：块拼接、空块与脏块跳过、usage 与 finish_reason 的采集。 */
class ChatStreamReaderTest {

    private static Response sseResponse(String body) {
        return new Response.Builder()
                .request(new Request.Builder().url("https://api.siliconflow.cn/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.get("text/event-stream"), body))
                .build();
    }

    @Test
    void joinsDeltasAndReportsProgress() throws IOException {
        String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"},\"finish_reason\":null}]}\n\n"
                + ": keep-alive\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":null},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":26,\"completion_tokens\":14,\"total_tokens\":40}}\n\n"
                + "data: [DONE]\n\n";
        List<String> progress = new ArrayList<>();
        ChatStreamReader.StreamResult result = ChatStreamReader.read(sseResponse(sse), progress::add);
        assertEquals("你好", result.content());
        // 进度回调拿到的是"到目前为止的累积文本"，首个空块（角色块）不产生回调。
        assertEquals(List.of("你", "你好"), progress);
        assertEquals("stop", result.finishReason());
        assertNotNull(result.usage());
        assertEquals(14, result.usage().completionTokens());
    }

    @Test
    void keepsLastUsageWhenReceivedIncrementally() throws IOException {
        // SiliconFlow 每个 chunk 都带渐进 usage，逐块覆盖后应拿到终值。
        String sse = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\"},\"finish_reason\":null}],"
                + "\"usage\":{\"prompt_tokens\":26,\"completion_tokens\":1,\"total_tokens\":27}}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"b\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":26,\"completion_tokens\":2,\"total_tokens\":28}}\n\n"
                + "data: [DONE]\n\n";
        ChatStreamReader.StreamResult result = ChatStreamReader.read(sseResponse(sse), ignored -> { });
        assertEquals("ab", result.content());
        assertEquals(2, result.usage().completionTokens());
    }

    @Test
    void skipsMalformedChunksAndStopsAtDone() throws IOException {
        String sse = "data: {not json at all}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n"
                + "data: [DONE]\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"不应到达\"},\"finish_reason\":null}]}\n\n";
        ChatStreamReader.StreamResult result = ChatStreamReader.read(sseResponse(sse), ignored -> { });
        assertEquals("ok", result.content());
        assertEquals(null, result.finishReason());
        assertNull(result.usage());
    }

    @Test
    void failsWhenResponseHasNoBody() {
        Response response = new Response.Builder()
                .request(new Request.Builder().url("https://api.siliconflow.cn/v1/chat/completions").build())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(null)
                .build();
        assertThrows(IOException.class, () -> ChatStreamReader.read(response, ignored -> { }));
    }
}
