package com.example.rag.profile;

import com.example.rag.observability.TokenUsage;

import java.io.IOException;

/**
 * decide 输出未通过格式校验：把坏样本从异常里带出去。
 *
 * <p>产生于 2026-10-04 的全链路复盘：decide 的 fallback 行一直只有 error_message 一行字，
 * 模型当时输出了什么坏格式（多加了 Markdown 包裹、字段类型不对、字符串没转义……）没有留证，
 * 而这些"模型输出不合规范"的样本恰恰是微调最需要的难样本。旧实现把原始内容丢在 catch
 * 触不到的局部作用域里再包一层 IOException，输出随之丢失。
 *
 * <p>message 刻意沿用 {@link #MESSAGE} 原文：model_call_logs.error_message 的统计口径
 * 因此完全不变，坏样本的识别方式是 output_json 变非空，而不是 error_message 变样。
 *
 * <p>原始输出不在这里截断，也不在这里脱敏：天然上界是请求里的 {@code max_tokens=800}
 * （中文场景约 1~2KB，不值得为此增加一个可错点）；脱敏由 {@code ModelCallLogger}
 * 在写入的唯一入口统一执行，本对象只负责搬运。
 */
public final class ProfileDecisionFormatException extends IOException {
    /** 沿用旧版 IOException 的 message，保证 error_message 统计口径不因本次改动断裂。 */
    public static final String MESSAGE = "画像 Agent 返回内容未通过格式校验";

    private final String rawContent;
    private final TokenUsage usage;

    public ProfileDecisionFormatException(String rawContent, TokenUsage usage, RuntimeException cause) {
        super(MESSAGE, cause);
        this.rawContent = rawContent;
        this.usage = usage;
    }

    /** 模型返回的原始内容，未解析、未脱敏；失败发生得足够早时可能为 null。 */
    public String rawContent() {
        return rawContent;
    }

    /** 本次调用的 token 用量；异常发生在用量可读之前时为 null。 */
    public TokenUsage usage() {
        return usage;
    }
}
