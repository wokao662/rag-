package com.example.rag.api;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
public class SystemStatusController {
    private final String applicationName;

    public SystemStatusController(@Value("${spring.application.name}") String applicationName) {
        this.applicationName = applicationName;
    }

    @GetMapping("/status")
    public StatusResponse status() {
        return new StatusResponse(applicationName, "ok", Instant.now());
    }

    public record StatusResponse(String application, String status, Instant timestamp) {
    }
}
