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
        when(accessCodes.redeem("TEST-8F3K2")).thenReturn("TEST-8F3K2");

        mockMvc.perform(post("/api/v1/auth/redeem")
                        .contentType("application/json")
                        .content("{\"code\":\"TEST-8F3K2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.externalId").value("TEST-8F3K2"));
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
