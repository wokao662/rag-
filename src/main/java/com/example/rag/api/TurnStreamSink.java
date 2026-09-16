package com.example.rag.api;

import com.example.rag.profile.TurnStream;
import com.example.rag.profile.UserProfileService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把一轮对话的进度与增量写成 SSE 事件。事件协议（前端 app.js 的解析与此同源）：
 * <pre>
 *   stage  {"stage":"extract|decide|recommend"}   // 粗粒度进度，驱动"正在…"提示
 *   delta  {"text":"..."}                          // 回复/推荐开头的增量文本
 *   final  TurnResult 本体                          // 与非流式接口响应完全同构，前端以它为准
 *   error  {"message":"..."}                       // 处理失败时唯一的事件，发完即结束
 * </pre>
 *
 * <p>客户端断开（send 抛 IOException）后整条流静默作废：跳过后续发送，但处理线程会照常把
 * 这一轮跑完并落库——用户刷掉页面不该让已生成的推荐丢失。
 */
public final class TurnStreamSink implements TurnStream {
    private final SseEmitter emitter;
    private final AtomicBoolean broken = new AtomicBoolean();

    public TurnStreamSink(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void stage(String stage) {
        send("stage", Map.of("stage", stage));
    }

    @Override
    public void delta(String text) {
        send("delta", Map.of("text", text));
    }

    @Override
    public void finished(UserProfileService.TurnResult result) {
        send("final", result);
    }

    public void error(String message) {
        send("error", Map.of("message", message));
    }

    /** 正常结束流。已被标记断开或已结束时什么都不做。 */
    public void complete() {
        if (broken.get()) return;
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 已断开/已完成的 emitter 再 complete 会抛异常，此时本来也无需再做任何事。
        }
    }

    private void send(String event, Object data) {
        if (broken.get()) return;
        synchronized (this) {
            if (broken.get()) return;
            try {
                emitter.send(SseEmitter.event().name(event).data(data));
            } catch (IOException | RuntimeException closed) {
                // 客户端断开（IOException）或响应已不可写（IllegalStateException 等）。
                // 静默作废整条流，让上面的业务逻辑继续跑完落库。
                broken.set(true);
            }
        }
    }
}
