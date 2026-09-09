package com.example.rag.api;

import com.example.rag.profile.UserProfileService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/users/{externalId}")
public class UserProfileController {
    private final UserProfileService profileService;

    public UserProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @PostMapping("/conversations")
    @ResponseStatus(HttpStatus.CREATED)
    public UserProfileService.ConversationStarted startConversation(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return profileService.startConversation(externalId);
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public UserProfileService.TurnResult sendMessage(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId,
            @PathVariable UUID conversationId,
            @Valid @RequestBody SendMessageRequest request
    ) {
        return profileService.processMessage(externalId, conversationId, request.content());
    }

    @GetMapping("/profile")
    public UserProfileService.ProfileResult getProfile(
            @PathVariable @NotBlank @Size(max = 128)
            @Pattern(regexp = "[A-Za-z0-9._-]+") String externalId
    ) {
        return profileService.getProfile(externalId);
    }

    public record SendMessageRequest(
            @NotBlank(message = "content 不能为空")
            @Size(max = 10_000, message = "content 不能超过 10000 个字符")
            String content
    ) {
    }
}
