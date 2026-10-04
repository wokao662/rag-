package com.example.rag.profile;

import com.example.rag.observability.TokenUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 这个异常是微调难样本的搬运工：原始输出与用量必须原样到达调用方，
 * 且 message 必须与历史口径一字不差，否则 error_message 统计会断裂。
 */
class ProfileDecisionFormatExceptionTest {

    @Test
    void carriesRawContentAndUsage() {
        TokenUsage usage = new TokenUsage(831, 133, 964);
        RuntimeException cause = new IllegalStateException("missing field: action");

        ProfileDecisionFormatException exception =
                new ProfileDecisionFormatException("{\"action\": \"maybe\"", usage, cause);

        assertEquals("{\"action\": \"maybe\"", exception.rawContent());
        assertEquals(usage, exception.usage());
        assertSame(cause, exception.getCause());
    }

    @Test
    void keepsLegacyMessageWording() {
        ProfileDecisionFormatException exception =
                new ProfileDecisionFormatException("not even json", null, new RuntimeException());

        assertEquals("画像 Agent 返回内容未通过格式校验", exception.getMessage());
        assertEquals(ProfileDecisionFormatException.MESSAGE, exception.getMessage());
        assertNull(exception.usage());
    }
}
