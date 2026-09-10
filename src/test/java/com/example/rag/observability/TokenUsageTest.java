package com.example.rag.observability;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * token 用量解析的单元测试。这一层的原则是“解析不出来就当没有”：用量属于观测数据，
 * 不能因为它缺失或格式异常，就把一次内容正常的模型调用判成失败。
 *
 * <p>断言整个 record 而不是逐个字段：字段是 {@code Integer}，逐个断言要么碰上
 * {@code assertEquals} 的重载歧义，要么在值为 null 时抛 NPE 而不是给出清晰的失败信息。
 */
class TokenUsageTest {

    @Test
    void readsAllThreeCountsFromApiResponse() {
        JsonObject response = JsonParser.parseString("""
                {"choices":[{"message":{"content":"{}"}}],
                 "usage":{"prompt_tokens":6123,"completion_tokens":412,"total_tokens":6535}}
                """).getAsJsonObject();

        assertEquals(new TokenUsage(6123, 412, 6535), TokenUsage.fromApi(response));
    }

    @Test
    void returnsNullWhenUsageIsAbsentOrUnreadable() {
        // 这些情况一律返回 null 而不是抛异常。抛出去会让调用方把一次成功的模型调用记成 failed，
        // 而观测数据不该有这种权力。
        assertNull(TokenUsage.fromApi(null));
        assertNull(TokenUsage.fromApi(new JsonObject()));
        assertNull(TokenUsage.fromApi(responseWith("\"usage\":null")));
        assertNull(TokenUsage.fromApi(responseWith("\"usage\":\"6535\"")));
        // usage 存在但一个计数都读不出来时同样当作没有，避免库里出现一行三列全 NULL 的记录，
        // 让“到底有没有采集到”这件事变得含糊。
        assertNull(TokenUsage.fromApi(responseWith("\"usage\":{}")));
        assertNull(TokenUsage.fromApi(responseWith("\"usage\":{\"prompt_tokens\":\"很多\"}")));
    }

    @Test
    void keepsPartialUsageWithMissingFieldsNull() {
        // 只报了一部分也照记。缺失的字段留 null，不补 0：0 是一个真实可能的用量值，
        // 用它表示未知会把两者混为一谈。
        assertEquals(new TokenUsage(6123, null, null),
                TokenUsage.fromApi(responseWith("\"usage\":{\"prompt_tokens\":6123}")));
        assertEquals(new TokenUsage(null, 412, null),
                TokenUsage.fromApi(responseWith(
                        "\"usage\":{\"prompt_tokens\":\"很多\",\"completion_tokens\":412}")));
    }

    @Test
    void doesNotDeriveTotalFromPromptPlusCompletion() {
        // 刻意不补算 total。补算会让“API 没报”与“API 报了这个数”在库里长得一模一样，
        // 而换成带思维链的模型后真实 total 会包含 reasoning token，补算值就与计费值偏离，
        // 且再也没有办法从数据里分辨哪一行是补出来的。
        assertEquals(new TokenUsage(6123, 412, null),
                TokenUsage.fromApi(responseWith(
                        "\"usage\":{\"prompt_tokens\":6123,\"completion_tokens\":412}")));
    }

    private static JsonObject responseWith(String usageFragment) {
        return JsonParser.parseString("{\"choices\":[]," + usageFragment + "}").getAsJsonObject();
    }
}
