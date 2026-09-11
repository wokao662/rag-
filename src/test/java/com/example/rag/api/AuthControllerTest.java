package com.example.rag.api;

import com.example.rag.auth.AccessCodeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({AuthController.class, ApiExceptionHandler.class})
class AuthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessCodeService accessCodes;

    @Test
    void redeemsValidCode() throws Exception {
        when(accessCodes.redeem("TEST-8F3K2"))
                .thenReturn(new AccessCodeService.Redemption("TEST-8F3K2", AccessCodeService.ROLE_TESTER));

        mockMvc.perform(post("/api/v1/auth/redeem")
                        .contentType("application/json")
                        .content("{\"code\":\"TEST-8F3K2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("TEST-8F3K2"))
                .andExpect(jsonPath("$.role").value("tester"));
    }

    @Test
    void returnsReviewerRoleSoTheClientCanGateTheReviewUi() throws Exception {
        // role 必须出现在兑换响应里：它是前端判断“要不要展示审核入口”的唯一依据，也是
        // access_codes.role 当前的运行时消费点。漏掉它，那一列就只是库里的摆设，
        // 而“存了但没人读”的字段等于没有这个机制。
        when(accessCodes.redeem("REVIEWER-01"))
                .thenReturn(new AccessCodeService.Redemption("REVIEWER-01", AccessCodeService.ROLE_REVIEWER));

        mockMvc.perform(post("/api/v1/auth/redeem")
                        .contentType("application/json")
                        .content("{\"code\":\"REVIEWER-01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("REVIEWER-01"))
                .andExpect(jsonPath("$.role").value("reviewer"));
    }

    @Test
    void rejectsInvalidCode() throws Exception {
        when(accessCodes.redeem("WRONG-CODE"))
                .thenThrow(new AccessCodeService.UnauthorizedException("访问码无效或已被停用"));

        mockMvc.perform(post("/api/v1/auth/redeem")
                        .contentType("application/json")
                        .content("{\"code\":\"WRONG-CODE\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("未授权"));
    }
}
