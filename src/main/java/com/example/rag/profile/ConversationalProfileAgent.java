package com.example.rag.profile;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** 根据当前画像和对话上下文，动态判断是继续追问还是进入推荐。 */
public final class ConversationalProfileAgent implements AutoCloseable {
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    public static final String MODEL = "Qwen/Qwen3-32B";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String SYSTEM_PROMPT = """
            你是一名耐心、尊重用户的学习画像访谈助手。你的任务不是诊断用户，
            而是判断现有信息是否足以从学习策略知识库中做出一轮有帮助的推荐。

            判断原则：
            1. 重点理解用户当前要解决的学习问题、学习内容或目标，以及明显约束。
            2. 不要求一次收集所有字段；只要能够做出初步且有针对性的推荐即可 recommend。
            3. 如果缺少会显著改变推荐结果的信息，action 设为 ask，并只提出一个自然、具体的问题。
            4. 优先追问最有信息价值的问题，避免重复询问用户已经回答的内容。
            5. 区分事实和推测。不能判断用户是否“说真话”，只能指出前后信息冲突并温和确认。
            6. 不推断疾病、智力、人格、家庭背景等敏感属性，不使用治疗或诊断性措辞。
            7. reason 简短说明决定依据；不要给学习方法，推荐由后续 RAG 模块完成。
            8. 只返回合法 JSON，不要输出 Markdown 或思考过程。

            输出结构固定为：
            {
              "action": "ask 或 recommend",
              "ready": true或false,
              "confidence": 0到1,
              "reason": "判断依据",
              "missingInformation": ["仍然重要的未知信息"],
              "conflicts": ["需要用户确认的冲突"],
              "nextQuestion": "只包含一个问题；recommend 时为空字符串"
            }
            """;

    private final String apiKey;
    private final ProfileDecisionValidator validator = new ProfileDecisionValidator();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    public ConversationalProfileAgent(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        this.apiKey = apiKey;
    }

    public ProfileDecisionValidator.Decision decide(
            JsonObject profile,
            List<MessageRepository.StoredMessage> recentMessages
    ) throws IOException {
        JsonObject context = new JsonObject();
        context.add("profile", profile == null ? new JsonObject() : profile);
        JsonArray history = new JsonArray();
        recentMessages.forEach(stored -> {
            JsonObject message = new JsonObject();
            message.addProperty("role", stored.role());
            message.addProperty("content", stored.content());
            history.add(message);
        });
        context.add("recentMessages", history);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("temperature", 0.2);
        body.addProperty("max_tokens", 800);
        body.addProperty("stream", false);
        body.addProperty("enable_thinking", false);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", "请根据以下画像和最近对话决定下一步：\n" + GSON.toJson(context)));
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("画像 Agent 请求失败 (HTTP " + response.code() + "): " + responseBody);
            }
            try {
                JsonObject apiResponse = JsonParser.parseString(responseBody).getAsJsonObject();
                String content = apiResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                return validator.validate(JsonParser.parseString(content).getAsJsonObject());
            } catch (RuntimeException error) {
                throw new IOException("画像 Agent 返回内容未通过格式校验", error);
            }
        }
    }

    private static JsonObject message(String role, String content) {
        JsonObject message = new JsonObject();
        message.addProperty("role", role);
        message.addProperty("content", content);
        return message;
    }

    @Override
    public void close() {
        client.dispatcher().cancelAll();
        client.dispatcher().executorService().shutdownNow();
        client.connectionPool().evictAll();
    }
}
