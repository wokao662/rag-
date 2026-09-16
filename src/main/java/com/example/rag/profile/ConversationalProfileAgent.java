package com.example.rag.profile;

import com.example.rag.llm.ChatStreamReader;
import com.example.rag.llm.JsonFieldStreamExtractor;
import com.example.rag.llm.ModelJson;
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
import java.util.function.Consumer;

/** 根据当前画像和对话上下文，动态判断是继续追问还是进入推荐。 */
public final class ConversationalProfileAgent implements AutoCloseable {
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    // 模型选择（2026-09-16 换型）：决策回复面向用户（~110-130 token 中文），
    // 换 GLM-5.2 兼顾质量与稳定（原 Qwen/Qwen3-32B 排队尖峰 28s~185s，是"回得慢"主因）。
    public static final String MODEL = "zai-org/GLM-5.2";
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final Gson GSON = new GsonBuilder().create();
    private static final String SYSTEM_PROMPT = """
            你是一名耐心、温暖、懂学习方法的学习伙伴。你一边和用户自然聊天，
            一边判断现有信息是否足以做出一轮真正有帮助的推荐。

            核心定位：你是来帮用户找到适合他的学习方法的，不是来陪聊的。
            每轮回复都要让用户感到“再聊下去，我能拿到对我有用的方法”。

            回复方式（action 为 ask 时）：nextQuestion 输出一段简短自然、有温度的话，
            而不是一句光秃秃的问题，它应当包含：
              1) 先接住用户刚说的内容——用一句具体的回应让他感到被听见，
                 不要用“我理解你的感受”这类空话，要针对他刚说的具体细节；
              2) 可以给一个轻量的即时小提示或一句鼓励，一句话即可，是引子不是完整方法；
              3) 末尾自然引出一个问题。
              整段控制在两三句话，不换行、不列清单。

            推进与克制（防止变成纯闲聊）：
              - 你的目标不是延长对话，而是尽快帮用户拿到方法；通常 2-4 轮、信息足够时立即 recommend。
              - 不要为了多聊而追问无关紧要的细节；不要连续两轮追问同一类信息。
              - 用户若连续闲聊、不提供学习相关信息，温和地把话题引回学习目标。
              - 完整、可操作的学习方法只在 recommend 阶段给出；ask 阶段只给一句话引子，保留获得感。

            判断原则：
            1. 重点理解用户当前要解决的学习问题、学习内容或目标，以及明显约束。
            2. 不要求一次收集所有字段；只要能做出初步且有针对性的推荐即可 recommend。
            3. 区分事实和推测；不能判断用户是否“说真话”，只能指出前后信息冲突并温和确认。
            4. 不推断疾病、智力、人格、家庭背景等敏感属性，不使用治疗或诊断性措辞。
            5. reason 简短说明决定依据；不要在 reason 里给学习方法，推荐由后续 RAG 模块完成。
            6. 只返回合法 JSON，不要输出 Markdown 或思考过程。

            输出结构固定为：
            {
              "action": "ask 或 recommend",
              "ready": true或false,
              "confidence": 0到1,
              "nextQuestion": "ask 时：上面要求的那段有温度的回复（接住用户+可选小提示+末尾一个问题）；recommend 时为空字符串",
              "reason": "判断依据",
              "missingInformation": ["仍然重要的未知信息"],
              "conflicts": ["需要用户确认的冲突"]
            }
            """;

    private final String apiKey;
    private final ProfileDecisionValidator validator = new ProfileDecisionValidator();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build();

    public ConversationalProfileAgent(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("API Key 不能为空");
        this.apiKey = apiKey;
    }

    public ModelReply<ProfileDecisionValidator.Decision> decide(
            JsonObject profile,
            List<MessageRepository.StoredMessage> recentMessages
    ) throws IOException {
        JsonObject body = decideBody(profile, recentMessages);
        body.addProperty("stream", false);

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
                // usage 与 choices 同层，且必须在校验内容之前取出来：校验失败会抛异常，
                // 而那时 token 已经花掉了，用量却再也拿不回来。
                TokenUsage usage = TokenUsage.fromApi(apiResponse);
                String content = apiResponse.getAsJsonArray("choices").get(0).getAsJsonObject()
                        .getAsJsonObject("message").get("content").getAsString();
                return new ModelReply<>(
                        validator.validate(ModelJson.parseObject(content)), usage);
            } catch (RuntimeException error) {
                throw new IOException("画像 Agent 返回内容未通过格式校验", error);
            }
        }
    }

    /**
     * decide 的流式版本：nextQuestion 字段的增量文本在生成过程中就交给 onReplyDelta，
     * 用户在整段 JSON 完成前先看到回复逐字出现。校验与 decide 完全同源，失败抛相同的
     * IOException，调用方的兜底逻辑不需要区分两种形态。
     */
    public ModelReply<ProfileDecisionValidator.Decision> decideStream(
            JsonObject profile,
            List<MessageRepository.StoredMessage> recentMessages,
            Consumer<String> onReplyDelta
    ) throws IOException {
        JsonObject body = decideBody(profile, recentMessages);
        body.addProperty("stream", true);
        JsonObject streamOptions = new JsonObject();
        streamOptions.addProperty("include_usage", true);
        body.add("stream_options", streamOptions);

        Request request = new Request.Builder()
                .url(API_URL)
                .header("Authorization", "Bearer " + apiKey)
                .post(RequestBody.create(body.toString(), JSON))
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String responseBody = response.body() == null ? "" : response.body().string();
                throw new IOException("画像 Agent 请求失败 (HTTP " + response.code() + "): " + responseBody);
            }
            JsonFieldStreamExtractor extractor = new JsonFieldStreamExtractor("nextQuestion");
            ChatStreamReader.StreamResult stream = ChatStreamReader.read(response, accumulated -> {
                String delta = extractor.accept(accumulated);
                if (!delta.isEmpty()) onReplyDelta.accept(delta);
            });
            try {
                return new ModelReply<>(
                        validator.validate(ModelJson.parseObject(stream.content())), stream.usage());
            } catch (RuntimeException error) {
                throw new IOException("画像 Agent 返回内容未通过格式校验", error);
            }
        }
    }

    /** stream 开关由两个入口各自补上：非流式路径的行为不因本次改动而变。 */
    private JsonObject decideBody(JsonObject profile, List<MessageRepository.StoredMessage> recentMessages) {
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
        body.addProperty("temperature", 0.5);
        body.addProperty("max_tokens", 800);
        // 关思维链 + 不用 response_format:json_object：实测该结构化模式在 SiliconFlow 上
        // 会劣化到 17~40 秒击穿 30 秒读超时，去掉后靠 system prompt 约束 + ModelJson 兜底解析。
        body.addProperty("enable_thinking", false);
        JsonArray messages = new JsonArray();
        messages.add(message("system", SYSTEM_PROMPT));
        messages.add(message("user", "请根据以下画像和最近对话决定下一步：\n" + GSON.toJson(context)));
        body.add("messages", messages);
        return body;
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
