package com.example.rag.observability;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * 一次模型调用的 token 用量。
 *
 * <p>三个字段都是 {@code Integer} 而不是 {@code int}：失败与本地兜底路径根本没有调用模型，
 * 此时“未知”和“0 token”是两件不同的事。存 NULL 才诚实，存 0 会把这些没花钱的调用
 * 混进平均用量里，把真实单价拉低。
 *
 * <p>拆开存 prompt 与 completion 而不是只存总数，是因为诊断延迟必须能区分输入大还是输出大——
 * 两者的处理办法完全相反：输入大要裁知识负载，输出大要压 max_tokens。只有一个总数的话，
 * 永远分不清该动哪一边。
 */
public record TokenUsage(Integer promptTokens, Integer completionTokens, Integer totalTokens) {

    /**
     * 从 OpenAI 兼容响应的 {@code usage} 对象解析用量。缺失、为 null 或字段不是数字时返回
     * {@code null}，不抛异常：用量是观测数据，不该因为它解析不出来就把一次成功的模型调用
     * 判成失败。
     *
     * <p>{@code total_tokens} 缺失时不用 prompt 加 completion 补算。补算会让“API 没报”与
     * “API 报了这个数”在库里长得一模一样，而两者含义不同：换成带思维链的模型之后，
     * 真实 total 会包含 reasoning token，补算值就会与计费值偏离，而库里再也看不出哪个是哪个。
     */
    public static TokenUsage fromApi(JsonObject apiResponse) {
        if (apiResponse == null) return null;
        JsonElement element = apiResponse.get("usage");
        if (element == null || !element.isJsonObject()) return null;
        JsonObject usage = element.getAsJsonObject();
        Integer prompt = integerOrNull(usage, "prompt_tokens");
        Integer completion = integerOrNull(usage, "completion_tokens");
        Integer total = integerOrNull(usage, "total_tokens");
        // 三个都读不出来就当作没有 usage。存一行全 NULL 的记录没有坏处，但返回 null 让
        // “这次到底有没有采集到”在代码里只有一个判断，不必到处检查三个字段是否都为空。
        if (prompt == null && completion == null && total == null) return null;
        return new TokenUsage(prompt, completion, total);
    }

    private static Integer integerOrNull(JsonObject usage, String field) {
        JsonElement value = usage.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        return value.getAsInt();
    }
}
