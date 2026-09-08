package com.example.rag.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SystemStatusController.class)
@TestPropertySource(properties = "spring.application.name=test-rag")
class SystemStatusControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsApplicationStatus() throws Exception {
        mockMvc.perform(get("/api/v1/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application").value("test-rag"))
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
