package com.example.rag.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * intent 是观测字段（docs/intent-taxonomy.md）：宽容解析，标注失败一律归 unknown，
 * 绝不因标注问题拖垮真实回答——这与 action 等硬校验字段的"不合格即抛"正好相反。
 */
class ProfileDecisionValidatorTest {
    private final ProfileDecisionValidator validator = new ProfileDecisionValidator();

    @Test
    void acceptsWhitelistedIntent() {
        JsonObject output = validOutput();
        output.addProperty("intent", "explore");

        assertEquals("explore", validator.validate(output).intent());
    }

    @Test
    void nonWhitelistedIntentFallsBackToUnknown() {
        JsonObject output = validOutput();
        output.addProperty("intent", "something_else");

        assertEquals("unknown", validator.validate(output).intent());
    }

    @Test
    void missingIntentFallsBackToUnknown() {
        assertEquals("unknown", validator.validate(validOutput()).intent());
    }

    @Test
    void nonStringIntentFallsBackToUnknown() {
        JsonObject output = validOutput();
        output.addProperty("intent", 42);

        assertEquals("unknown", validator.validate(output).intent());
    }

    /** 一份能通过现有硬校验的最小合法输出。 */
    private static JsonObject validOutput() {
        JsonObject output = new JsonObject();
        output.addProperty("action", "recommend");
        output.addProperty("ready", true);
        output.addProperty("confidence", 0.9);
        output.addProperty("reason", "测试依据");
        output.addProperty("nextQuestion", "");
        output.add("missingInformation", new JsonArray());
        output.add("conflicts", new JsonArray());
        return output;
    }
}
