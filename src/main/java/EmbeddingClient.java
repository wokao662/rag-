import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 调用硅基流动 Qwen3 Embedding API。 */
public final class EmbeddingClient implements AutoCloseable {
    public static final String MODEL = "Qwen/Qwen3-Embedding-4B";
    public static final int DIMENSION = 1024;
    public static final int MAX_BATCH_SIZE = 10;

    private static final String API_URL = "https://api.siliconflow.cn/v1/embeddings";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private final String apiKey;

    public EmbeddingClient(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("缺少 SILICONFLOW_API_KEY 环境变量");
        }
        this.apiKey = apiKey.trim();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .build();
    }

    public List<List<Float>> embedDocuments(List<String> texts) throws IOException {
        return embed(texts);
    }

    public List<Float> embedQuery(String text) throws IOException {
        String instructedQuery = "Instruct: 根据学习者的情况检索最适合的学习策略和具体方法\nQuery: " + text;
        return embed(List.of(instructedQuery)).get(0);
    }

    private List<List<Float>> embed(List<String> texts) throws IOException {
        if (texts.isEmpty() || texts.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("每次必须提交 1～" + MAX_BATCH_SIZE + " 条文本");
        }

        JsonArray textArray = new JsonArray();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                throw new IllegalArgumentException("Embedding 文本不能为空");
            }
            textArray.add(text);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.add("input", textArray);
        body.addProperty("dimensions", DIMENSION);
        body.addProperty("encoding_format", "float");

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Embedding 请求失败 (HTTP " + response.code() + "): " + responseBody);
            }

            JsonObject root = gson.fromJson(responseBody, JsonObject.class);
            JsonArray embeddings = root.getAsJsonArray("data");
            List<List<Float>> ordered = new ArrayList<>(Collections.nCopies(texts.size(), null));
            for (int i = 0; i < embeddings.size(); i++) {
                JsonObject item = embeddings.get(i).getAsJsonObject();
                int index = item.has("index") ? item.get("index").getAsInt() : i;
                JsonArray values = item.getAsJsonArray("embedding");
                if (values.size() != DIMENSION) {
                    throw new IOException("Embedding 维度异常：期望 " + DIMENSION + "，实际 " + values.size());
                }
                List<Float> vector = new ArrayList<>(DIMENSION);
                values.forEach(value -> vector.add(value.getAsFloat()));
                ordered.set(index, vector);
            }
            if (ordered.stream().anyMatch(vector -> vector == null)) {
                throw new IOException("Embedding 返回数量与输入数量不一致");
            }
            return ordered;
        }
    }

    @Override
    public void close() {
        httpClient.dispatcher().executorService().shutdown();
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
