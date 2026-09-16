package com.example.rag.profile;

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

/** 使用聊天模型从用户原话中抽取结构化画像候选；不直接写数据库。 */
public final class UserProfileExtractor implements AutoCloseable {
    private static final String API_URL = "https://api.siliconflow.cn/v1/chat/completions";
    // 模型选择（2026-09-16 换型）：抽取是短结构化输出（~50-200 token），原 Qwen/Qwen3-32B 在
    // SiliconFlow 上排队尖峰严重（8 token 请求实测 28s~185s，频繁击穿 30s 读超时）。
    // DeepSeek-V4-Flash 同负载实测 1.3s、JSON 干净且 evidence 逐字完整；
    // 注意它在长文生成场景很慢（~845 token 需 75s），不适合本路以外的用途。
    public static final String MODEL = "deepseek-ai/DeepSeek-V4-Flash";
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

            你还要判断这条消息属于哪个「目标情境」。一个目标情境 = 用户当前正在攻克的一个
            具体学习任务（例如“四级听力”“数学期末考”）。输入里的 existingEpisodes 是用户
            已有的活跃情境清单，每项含 id、label、goal、content。episodeDecision 规则：
            1. 消息明显在继续清单里的某个情境（同一学习目标或内容）时，action="continue"，
               并给出该情境的 episodeId。
            2. 这是清单里没有的、新的学习目标时，action="new"，并给出一个简短中文 label
               （不超过12个字，概括这个情境，如“四级听力”），episodeId 留空字符串。
            3. 拿不准时优先 continue 到最接近的活跃情境，不要轻易开新情境，避免情境碎片化。
            4. 同一学科下的不同任务算不同情境（“英语期末完形”与“四级听力”是两个情境）。
            5. existingEpisodes 为空时，一律 action="new" 并给出 label。

            输出结构：
            {
              "episodeDecision": {
                "action": "continue 或 new",
                "episodeId": "continue 时填对应 id；new 时填空字符串",
                "label": "new 时填简短情境名；continue 时填空字符串"
              },
              "updates": {
                "字段名": {
                  "value": "与字段类型一致的值",
                  "sourceType": "explicit",
                  "confidence": 1.0,
                  "evidence": "当前消息中的原文"
                }
              }
            }
            没有可提取的画像字段时 updates 输出 {}，但 episodeDecision 仍必须给出。
            """;

    private final String apiKey;
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
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
        // 关思维链 + 不用 response_format:json_object：实测该结构化模式在 SiliconFlow 上
        // 会劣化到 17~40 秒击穿 30 秒读超时，去掉后靠 system prompt 约束 + ModelJson 兜底解析，
        // 同样的输出降到 1 秒级。详见 ModelJson 类注释。
        body.addProperty("enable_thinking", false);

        JsonObject input = new JsonObject();
        // 只给活跃情境摘要（id/label/goal/content），不给整份画像：既省 token，
        // 又避免模型从旧画像反推新信息（抽取只应基于当前消息）。
        input.add("existingEpisodes", EpisodeProfile.episodeSummaries(existingProfile));
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
        messages.add(message("user", "请只从currentUserMessage抽取画像更新，并判断目标情境归属：\n" + GSON.toJson(input)));
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
                JsonObject extraction = ModelJson.parseObject(content);
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
