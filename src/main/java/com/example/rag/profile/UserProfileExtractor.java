package com.example.rag.profile;

import com.example.rag.observability.ModelReply;
import com.example.rag.observability.TokenUsage;
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

/** 使用聊天模型从用户原话中抽取结构化画像候选；不直接写数据库。 */
public final class UserProfileExtractor implements AutoCloseable {
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    public static final String MODEL = "Qwen/Qwen3-32B";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String SYSTEM_PROMPT = """
            你是学习用户画像信息抽取器，不负责提供学习建议。

            只提取用户当前消息中明确表达的信息，不使用常识猜测，不根据已有画像反推新信息。
            允许字段：
            - learningGoal：学习目标，字符串
            - learningContent：学习内容，字符串
            - mainDifficulty：主要困难，字符串
            - availableMinutesPerDay：每天可用学习分钟数，整数
            - daysUntilDeadline：距离考试或截止日期的天数，整数
            - preferredLearningStyle：用户明确说出的学习方式偏好，字符串
            - triedMethods：用户明确说已经尝试过的方法，字符串数组

            规则：
            1. 没有明确表达的字段不要输出。
            2. 每个字段必须包含 value、sourceType、confidence、evidence。
            3. sourceType 当前只能是 explicit。
            4. evidence 必须逐字摘自当前用户消息，不能改写。
            5. confidence 范围为0到1；明确原话通常为1。
            6. 可以做确定性单位换算，例如“2小时”转换成120分钟、“两个月后”近似转换成60天，但 evidence 仍保留原话。
            7. 不推断性格、智力、疾病、家庭背景或其他敏感属性。
            8. 只输出合法JSON，不输出Markdown和分析过程。

            输出结构：
            {
              "updates": {
                "字段名": {
                  "value": "与字段类型一致的值",
                  "sourceType": "explicit",
                  "confidence": 1.0,
                  "evidence": "当前消息中的原文"
                }
              }
            }
            没有可提取信息时输出 {"updates":{}}。
            """;

    private final String apiKey;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build();

    public UserProfileExtractor(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API Key不能为空");
        this.apiKey = apiKey;
    }

    public ModelReply<JsonObject> extract(
            JsonObject existingProfile,
            List<MessageRepository.StoredMessage> recentMessages,
            String currentMessage
    ) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("temperature", 0);
        body.addProperty("max_tokens", 1000);
        body.addProperty("stream", false);
        body.addProperty("enable_thinking", false);
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        JsonObject input = new JsonObject();
        input.add("existingProfile", existingProfile == null ? new JsonObject() : existingProfile);
        JsonArray history = new JsonArray();
        recentMessages.forEach(message -> {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            history.add(item);
        });
        input.add("recentMessages", history);
        input.addProperty("currentUserMessage", currentMessage);

        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", "请只从currentUserMessage抽取画像更新：\n" + GSON.toJson(input)));
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            String responseBody = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("画像抽取请求失败 (HTTP " + response.code() + "): " + responseBody);
            }
            JsonObject apiResponse = JsonParser.parseString(responseBody).getAsJsonObject();
            // usage 与 choices 同层。此前解析完 content 就把整个 apiResponse 丢掉了，用量跟着一起丢。
            TokenUsage usage = TokenUsage.fromApi(apiResponse);
            String content = apiResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                    .getAsJsonObject("message").get("content").getAsString();
            try {
                JsonObject extraction = JsonParser.parseString(content).getAsJsonObject();
                if (!extraction.has("updates") || !extraction.get("updates").isJsonObject()) {
                    throw new IllegalArgumentException("缺少updates对象");
                }
                return new ModelReply<>(extraction, usage);
            } catch (RuntimeException error) {
                throw new IOException("画像模型没有返回规定JSON：" + content, error);
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
