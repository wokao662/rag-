package com.example.rag.observability;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏规则的单元测试。这张表存的是用户原话，漏掉一类标识符就是真的泄露，
 * 而误伤学习数字（时长、次数、单词量）会毁掉训练数据，两个方向都必须测。
 */
class ModelCallSanitizerTest {

    @Test
    void scrubsChineseMobileNumber() {
        assertEquals("我的手机号是[手机号]，可以联系我",
                ModelCallSanitizer.scrubText("我的手机号是13812345678，可以联系我"));
    }

    @Test
    void scrubsIdCardIncludingTrailingX() {
        assertEquals("身份证[证件号]", ModelCallSanitizer.scrubText("身份证11010119900307451X"));
        assertEquals("身份证[证件号]", ModelCallSanitizer.scrubText("身份证110101199003074512"));
    }

    @Test
    void scrubsEmail() {
        assertEquals("邮箱是[邮箱]", ModelCallSanitizer.scrubText("邮箱是zhang.san+test@mail.example.com"));
    }

    @Test
    void scrubsBankCard() {
        assertEquals("卡号[卡号]", ModelCallSanitizer.scrubText("卡号6222021234567890123"));
    }

    @Test
    void scrubsLongDigitRunsAsCatchAll() {
        // 学号、工号、QQ 号都在这一类，靠 8 位以上连续数字兜住。
        assertEquals("学号[数字串]", ModelCallSanitizer.scrubText("学号20231234"));
    }

    @Test
    void keepsStudyNumbersIntact() {
        // 误伤这些数字就等于毁掉训练数据：它们正是画像要抽的字段值。
        String text = "我每天学习60分钟，一周3次，已经背了3000个单词，坚持了21天";
        assertEquals(text, ModelCallSanitizer.scrubText(text));
    }

    @Test
    void scrubsNestedJsonWithoutMutatingSource() {
        JsonObject source = new JsonObject();
        source.addProperty("currentUserMessage", "我手机号13812345678");
        source.addProperty("latencyMs", 12345678901L);
        source.addProperty("ready", true);
        JsonObject nested = new JsonObject();
        nested.addProperty("note", "邮箱 a@b.co");
        JsonArray array = new JsonArray();
        array.add("学号20231234");
        array.add(nested);
        array.add(7);
        source.add("history", array);

        JsonObject scrubbed = ModelCallSanitizer.scrub(source);

        assertEquals("我手机号[手机号]", scrubbed.get("currentUserMessage").getAsString());
        // 非字符串原样保留：数字型字段不是标识符，替换成占位符会破坏 JSON 类型。
        assertEquals(12345678901L, scrubbed.get("latencyMs").getAsLong());
        assertTrue(scrubbed.get("ready").getAsBoolean());
        JsonArray scrubbedArray = scrubbed.getAsJsonArray("history");
        assertEquals("学号[数字串]", scrubbedArray.get(0).getAsString());
        assertEquals("邮箱 [邮箱]", scrubbedArray.get(1).getAsJsonObject().get("note").getAsString());
        assertEquals(7, scrubbedArray.get(2).getAsInt());

        // 调用方记完日志还要继续用原对象（抽取结果要拿去做校验与合并），原地修改会把占位符带进画像。
        assertEquals("我手机号13812345678", source.get("currentUserMessage").getAsString());
        assertEquals("学号20231234", source.getAsJsonArray("history").get(0).getAsString());
    }

    @Test
    void handlesNullAndEmpty() {
        assertNull(ModelCallSanitizer.scrub(null));
        assertNull(ModelCallSanitizer.scrubText(null));
        assertEquals("", ModelCallSanitizer.scrubText(""));
        assertEquals(0, ModelCallSanitizer.scrub(new JsonObject()).size());
    }

    @Test
    void keepsJsonNullMembers() {
        JsonObject source = new JsonObject();
        source.add("missing", null);
        JsonObject scrubbed = ModelCallSanitizer.scrub(source);
        assertTrue(scrubbed.has("missing"));
        assertTrue(scrubbed.get("missing").isJsonNull());
        assertFalse(scrubbed.get("missing").isJsonObject());
    }
}
