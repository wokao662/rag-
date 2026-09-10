package com.example.rag.observability;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 模型调用日志的脱敏。
 *
 * <p>{@code model_call_logs.input_json} 是整个系统最大的隐私暴露面：它把送给模型的输入原文存下来
 * 当训练数据，而 {@code extract} 任务的输入里就有 {@code currentUserMessage}，即用户逐字说过的话。
 * 用户不会预期自己的原话被长期留存，一条泄露就足以让人再也不愿意说真话——而这个产品全靠用户说真话。
 *
 * <p>脱敏放在写入的唯一入口（{@link ModelCallLogger}），而不是让每个调用方自己记得处理：
 * 靠自觉的隐私保护等于没有保护。
 *
 * <p>替换成占位符而不是整条丢弃，是因为句子结构本身有训练价值：模型一学的是"从这句话里该抽出哪个
 * 画像字段"，直接标识符是噪声——它本来就不该被抽进画像。所以脱敏与训练质量是同向的，不是取舍。
 *
 * <p>处理不了的：姓名、学校、单位、住址这类没有固定格式的信息。它们只能靠保留期与访问控制兜住，
 * 或者将来接入命名实体识别。现在不假装已经解决。
 */
public final class ModelCallSanitizer {
    /** 18 位身份证（末位可能是 X）。必须排在手机号与长数字串之前，否则会被更宽的模式吃掉。 */
    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9Xx])[0-9]{17}[0-9Xx](?![0-9Xx])");
    /** 银行卡号：16 到 19 位纯数字。 */
    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9])[0-9]{16,19}(?![0-9])");
    /** 中国大陆手机号。 */
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])1[3-9][0-9]{9}(?![0-9])");
    /** 邮箱。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    /**
     * 8 位以上连续数字的兜底：学号、工号、QQ 号都在这一类。
     * 代价是会误伤用户真的在讨论的大数字，但学习对话里 8 位以上连续数字几乎都是标识符，
     * 误伤可以接受，漏掉不行。
     */
    private static final Pattern LONG_DIGITS = Pattern.compile("(?<![0-9])[0-9]{8,}(?![0-9])");

    private ModelCallSanitizer() {
    }

    /** 返回脱敏后的深拷贝；不修改入参，因为调用方在记日志之后还要继续用这个对象。 */
    public static JsonObject scrub(JsonObject source) {
        if (source == null) return null;
        JsonObject copy = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            copy.add(entry.getKey(), scrubElement(entry.getValue()));
        }
        return copy;
    }

    private static JsonElement scrubElement(JsonElement element) {
        if (element == null || element.isJsonNull()) return element;
        if (element.isJsonObject()) return scrub(element.getAsJsonObject());
        if (element.isJsonArray()) {
            JsonArray copy = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) copy.add(scrubElement(item));
            return copy;
        }
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            return new JsonPrimitive(scrubText(element.getAsString()));
        }
        return element;
    }

    /** 替换文本中的直接标识符。包级可见以便单测。 */
    static String scrubText(String text) {
        if (text == null || text.isEmpty()) return text;
        String scrubbed = ID_CARD.matcher(text).replaceAll("[证件号]");
        scrubbed = BANK_CARD.matcher(scrubbed).replaceAll("[卡号]");
        scrubbed = PHONE.matcher(scrubbed).replaceAll("[手机号]");
        scrubbed = EMAIL.matcher(scrubbed).replaceAll("[邮箱]");
        return LONG_DIGITS.matcher(scrubbed).replaceAll("[数字串]");
    }
}
