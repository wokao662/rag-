package com.example.rag.profile;

/**
 * 一轮对话处理过程中的实时输出口，由 API 层实现为 SSE 推送（见 api.TurnStreamSink）。
 *
 * <p>stage 是粗粒度进度（extract / decide / recommend），delta 是给用户看的文本增量
 * （回复或推荐开头的字段值），finished 里带着与非流式接口相同的 TurnResult——前端以它
 * 为准做最终渲染：中途的任何增量都可能因兜底而变得不权威。
 */
public interface TurnStream {
    void stage(String stage);

    void delta(String text);

    void finished(UserProfileService.TurnResult result);
}
