package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class HealthController {

    @GetMapping({"/health", "/api/v1/health"})
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "service", "java-bff"
        ));
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
