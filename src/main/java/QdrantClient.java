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
public final class QdrantClient {
    public static final String COLLECTION = "learning_strategies";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

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
}
