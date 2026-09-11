package com.example.rag.observability;

/**
 * 模型调用的返回内容，连同它的 token 用量。
 *
 * <p>这个类存在的唯一理由是把用量从发请求的地方带给记日志的地方。三个客户端
 * （{@code UserProfileExtractor}、{@code ConversationalProfileAgent}、
 * {@code RecommendationChatClient}）各自解析 {@code choices[0].message.content}，
 * 而 {@code usage} 就在同一层响应里；日志却由调用方记，因为只有调用方知道 userId、
 * conversationId，以及本次是成功、失败还是走了本地兜底。用量必须随返回值一起出来。
 *
 * <p>不用客户端的实例字段暂存用量：这三个都是单例 Bean，并发请求会互相覆盖，
 * 记进日志的就会是别人的用量。这种错误在单人自测时永远不出现，上线才炸。
 *
 * @param content 模型返回并已解析的内容，具体类型随客户端而异
 * @param usage   本次调用的 token 用量；响应里没有 {@code usage} 时为 {@code null}
 */
public record ModelReply<T>(T content, TokenUsage usage) {
}
