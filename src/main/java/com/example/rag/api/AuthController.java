package com.example.rag.api;

import com.example.rag.auth.AccessCodeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AccessCodeService accessCodes;

    public AuthController(AccessCodeService accessCodes) {
        this.accessCodes = accessCodes;
    }

    @PostMapping("/redeem")
    public RedeemResponse redeem(@Valid @RequestBody RedeemRequest request) {
        return new RedeemResponse(accessCodes.redeem(request.code()));
    }

    public record RedeemRequest(
            @NotBlank(message = "访问码不能为空")
            @Size(max = 64, message = "访问码不能超过 64 个字符")
            @Pattern(regexp = "[A-Za-z0-9._-]+", message = "访问码格式不正确")
            String code
    ) {
    }

    public record RedeemResponse(String externalId) {
    }
}
