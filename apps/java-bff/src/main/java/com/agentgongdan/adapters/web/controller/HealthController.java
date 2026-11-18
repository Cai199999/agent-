package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.app.ports.ai.AiAvailabilityPort;
import com.agentgongdan.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final AiAvailabilityPort availability;

    public HealthController(AiAvailabilityPort availability) {
        this.availability = availability;
    }

    @GetMapping({"/health", "/api/v1/health"})
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "service", "java-bff"
        ));
    }

    /**
     * AI 熔断器状态：前端据此展示降级提示，不暴露上游地址或密钥。
     */
    @GetMapping({"/health/ai", "/api/v1/health/ai"})
    public ApiResponse<Map<String, Object>> aiHealth() {
        Map<String, Object> scopes = availability.snapshot();
        boolean degraded = scopes.values().stream()
                .anyMatch(value -> value instanceof Map<?, ?> item
                        && !"CLOSED".equals(String.valueOf(item.get("state"))));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", degraded ? "DEGRADED" : "UP");
        data.put("scopes", scopes);
        return ApiResponse.ok(data);
    }

    @GetMapping({"/ready", "/api/v1/ready"})
    public ApiResponse<Map<String, Object>> ready() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "service", "java-bff",
                "ready", true
        ));
    }
}
