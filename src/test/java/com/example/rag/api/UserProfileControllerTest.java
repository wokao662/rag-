package com.example.rag.api;

import com.example.rag.profile.UserProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({UserProfileController.class, ApiExceptionHandler.class})
class UserProfileControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserProfileService profileService;

    @Test
    void createsConversation() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID conversationId = UUID.randomUUID();
        when(profileService.startConversation("web-user-001"))
                .thenReturn(new UserProfileService.ConversationStarted(userId, conversationId));

        mockMvc.perform(post("/api/v1/users/web-user-001/conversations"))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith("application/json;charset=UTF-8"))
                .andExpect(jsonPath("$.userId").value(userId.toString()))
                .andExpect(jsonPath("$.conversationId").value(conversationId.toString()));
    }

    @Test
    void rejectsBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/users/web-user-001/conversations/{id}/messages", UUID.randomUUID())
                        .contentType("application/json")
                        .content("{\"content\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("请求参数不合法"));
    }

    @Test
    void rejectsInvalidExternalId() throws Exception {
        mockMvc.perform(post("/api/v1/users/{externalId}/conversations", "invalid user"))
                .andExpect(status().isBadRequest());
    }
}
