package com.example.rag.recommendation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 通过 Qdrant REST API 创建集合、写入和检索向量。 */
public final class QdrantClient implements AutoCloseable {
    public static final String COLLECTION = "learning_strategies";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    /** 一页取回的点数。这是 Qdrant scroll 的单页语义，不是总量上限。 */
    private static final int SCROLL_PAGE_SIZE = 100;

    /**
     * 一次补齐最多取回多少个 chunk。
     *
     * <p>不设上限的话提示词就没有边界：召回固定取 5 个策略，而每个策略的 chunk 数会随内容
     * 加深而增长，全部拼进去迟早撑爆上下文窗口，那时推荐不是变慢而是整条链路报错。
     * 200 = 5 个策略 × 每个 40 chunk，而当前最厚的策略只有 12 chunk，所以语料正常加深的过程
     * 中碰不到它；真碰到了说明该做知识负载预算了（roadmap 里独立的一项），而不是继续调大这个数。
     *
     * <p>它与原先写死的 {@code limit=100} 的差别不在于数字，而在于碰到时会说话：命中总数与
     * 实际取回数的差值会进 model_call_logs，而不是像以前那样无错误无日志地丢掉一半资料。
     */
    private static final int MAX_EXPANDED_POINTS = 200;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();
    private final Gson gson = new Gson();
    private final String baseUrl;

    public QdrantClient(String baseUrl) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
    }

    public void ensureCollection(int dimension) throws IOException {
        String url = baseUrl + "/collections/" + COLLECTION;
        Request get = new Request.Builder().url(url).get().build();
        try (Response response = httpClient.newCall(get).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (response.isSuccessful()) {
                JsonObject vectors = gson.fromJson(body, JsonObject.class)
                        .getAsJsonObject("result").getAsJsonObject("config")
                        .getAsJsonObject("params").getAsJsonObject("vectors");
                int existingSize = vectors.get("size").getAsInt();
                String distance = vectors.get("distance").getAsString();
                if (existingSize != dimension || !"Cosine".equalsIgnoreCase(distance)) {
                    throw new IOException("Collection 已存在但配置不一致：size=" + existingSize
                            + ", distance=" + distance + "；请换集合名或确认后重建");
                }
                return;
            }
            if (response.code() != 404) {
                throw new IOException("检查 Qdrant Collection 失败 (HTTP " + response.code() + "): " + body);
            }
        }

        JsonObject vectorConfig = new JsonObject();
        vectorConfig.addProperty("size", dimension);
        vectorConfig.addProperty("distance", "Cosine");
        JsonObject createBody = new JsonObject();
        createBody.add("vectors", vectorConfig);
        executeJson("PUT", url, createBody);
        System.out.println("已创建 Qdrant Collection: " + COLLECTION);
    }

    public void upsert(List<JsonObject> points) throws IOException {
        JsonArray array = new JsonArray();
        points.forEach(array::add);
        JsonObject body = new JsonObject();
        body.add("points", array);
        executeJson("PUT", baseUrl + "/collections/" + COLLECTION + "/points?wait=true", body);
    }

    public JsonArray search(List<Float> vector, int limit) throws IOException {
        JsonArray queryVector = new JsonArray();
        vector.forEach(queryVector::add);
        JsonObject body = new JsonObject();
        body.add("query", queryVector);
        body.addProperty("limit", limit);
        body.addProperty("with_payload", true);
        JsonObject response = executeJson("POST",
                baseUrl + "/collections/" + COLLECTION + "/points/query", body);
        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject() && result.getAsJsonObject().has("points")) {
            return result.getAsJsonObject().getAsJsonArray("points");
        }
        throw new IOException("Qdrant 查询响应中缺少 result.points");
    }

    /**
     * 按策略 ID 拉回这些策略的全部 chunk，用于把召回片段补齐成完整资料。
     *
     * <p>分页取回，不再只取第一页：原先 {@code limit} 写死 100 且不处理 {@code next_page_offset}，
     * 命中超过 100 个 chunk 时后面的会被静默丢掉——没有异常也没有日志，模型只会拿着半个
     * 策略的资料给出缺少实施步骤的推荐，而这种推荐看上去完全正常。
     *
     * @return 取回的点与过滤器命中的总数；两者不等说明碰到了 {@link #MAX_EXPANDED_POINTS}
     */
    public Expansion findByStrategyIds(List<String> strategyIds) throws IOException {
        JsonObject filter = strategyIdFilter(strategyIds);
        int totalMatching = countMatching(filter);
        JsonArray points = scrollAll((offset, limit) -> scrollPage(filter, offset, limit),
                totalMatching, MAX_EXPANDED_POINTS);
        return new Expansion(points, totalMatching);
    }

    /**
     * 一次策略补齐的结果。
     *
     * @param points        取回的点，最多 {@link #MAX_EXPANDED_POINTS} 个
     * @param totalMatching 过滤器命中的总点数
     */
    public record Expansion(JsonArray points, int totalMatching) {
        /** 是否有点因为预算上限没被取回。 */
        public boolean truncated() {
            return totalMatching > points.size();
        }
    }

    /** 取一页 scroll 结果；{@code offset} 为 {@code null} 表示从头开始。 */
    interface PageFetcher {
        JsonObject fetch(JsonElement offset, int limit) throws IOException;
    }

    /**
     * 按 scroll 语义把分页结果拼成一个数组。
     *
     * <p>抽成包内可见的静态方法而不是内联在 {@link #findByStrategyIds} 里：分页有三个只在真实
     * Qdrant 上才暴露的陷阱（末页的 {@code next_page_offset} 是 JSON null 而不是缺失字段、
     * {@code offset} 是包含语义、空页会让循环原地打转），它们都能用假 fetcher 在测试里复现，
     * 而对着活的 Qdrant 造不出超过 100 个 chunk 的语料来触发。
     *
     * @param fetcher       取一页
     * @param totalMatching 过滤器命中的总点数，用作循环上界，避免为了探底多问一次空页
     * @param maxPoints     预算上限，取到这么多就停
     */
    static JsonArray scrollAll(PageFetcher fetcher, int totalMatching, int maxPoints) throws IOException {
        JsonArray points = new JsonArray();
        JsonElement offset = null;
        int budget = Math.min(totalMatching, maxPoints);
        while (points.size() < budget) {
            JsonObject page = fetcher.fetch(offset, Math.min(SCROLL_PAGE_SIZE, budget - points.size()));
            JsonArray returned = page.getAsJsonArray("points");
            // 空页必须停：offset 不变、points 不增，循环条件会一直成立，请求就挂在这里直到
            // 客户端超时。Qdrant 正常不会返回带 offset 的空页，但这是唯一会死循环的形状。
            if (returned.size() == 0) break;
            // 只追加到预算为止。Qdrant 会遵守 limit，但预算是本方法要保证的不变量，交给对端守
            // 就等于没守：totalMatching 是另一次请求问来的，两次之间有新点写入时它已经偏小，
            // 而 limit 也是拿它算的。
            int room = budget - points.size();
            for (int index = 0; index < Math.min(room, returned.size()); index++) {
                points.add(returned.get(index));
            }
            offset = page.get("next_page_offset");
            // 末页返回的是 "next_page_offset": null，字段存在但值为 JSON null，而 has() 对 JsonNull
            // 返回 true。只看 has() 会把 null 当成 offset 发回去。
            if (offset == null || offset.isJsonNull()) break;
        }
        return points;
    }

    private static JsonObject strategyIdFilter(List<String> strategyIds) {
        JsonArray any = new JsonArray();
        strategyIds.forEach(any::add);
        JsonObject match = new JsonObject();
        match.add("any", any);
        JsonObject condition = new JsonObject();
        condition.addProperty("key", "strategyId");
        condition.add("match", match);
        JsonArray must = new JsonArray();
        must.add(condition);
        JsonObject filter = new JsonObject();
        filter.add("must", must);
        return filter;
    }

    /**
     * 过滤器命中的点数。用 {@code exact} 而不是默认的估算：这个数字要写进日志当作“丢了多少”的
     * 依据，估算值会让人以为截断量在变而实际上只是 Qdrant 的近似算法在抖。
     */
    private int countMatching(JsonObject filter) throws IOException {
        JsonObject body = new JsonObject();
        body.add("filter", filter);
        body.addProperty("exact", true);
        JsonObject response = executeJson("POST",
                baseUrl + "/collections/" + COLLECTION + "/points/count", body);
        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject() && result.getAsJsonObject().has("count")) {
            return result.getAsJsonObject().get("count").getAsInt();
        }
        throw new IOException("Qdrant count 响应中缺少 result.count");
    }

    private JsonObject scrollPage(JsonObject filter, JsonElement offset, int limit) throws IOException {
        JsonObject body = new JsonObject();
        body.add("filter", filter);
        body.addProperty("limit", limit);
        body.addProperty("with_payload", true);
        body.addProperty("with_vector", false);
        // offset 是包含语义，而 Qdrant 给的 next_page_offset 已经是下一个尚未返回的点，所以直接
        // 传回去不重不漏，不需要自己算“最后一个 id 的下一个”。用 JsonElement 而不是 String：
        // 点 id 可以是字符串也可以是整数，取出来再塞回去能两种都不假设。
        if (offset != null) body.add("offset", offset);
        JsonObject response = executeJson("POST",
                baseUrl + "/collections/" + COLLECTION + "/points/scroll", body);
        JsonElement result = response.get("result");
        if (result != null && result.isJsonObject() && result.getAsJsonObject().has("points")) {
            return result.getAsJsonObject();
        }
        throw new IOException("Qdrant 查询响应中缺少 result.points");
    }

    private JsonObject executeJson(String method, String url, JsonObject body) throws IOException {
        RequestBody requestBody = RequestBody.create(body.toString(), JSON);
        Request.Builder builder = new Request.Builder().url(url);
        Request request = "PUT".equals(method) ? builder.put(requestBody).build() : builder.post(requestBody).build();
        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Qdrant 请求失败 (HTTP " + response.code() + "): " + responseBody);
            }
            return gson.fromJson(responseBody, JsonObject.class);
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().cancelAll();
        httpClient.dispatcher().executorService().shutdownNow();
        httpClient.connectionPool().evictAll();
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (IOException ignored) {
                // 无需因关闭可选缓存失败而影响程序退出。
            }
        }
    }
}
