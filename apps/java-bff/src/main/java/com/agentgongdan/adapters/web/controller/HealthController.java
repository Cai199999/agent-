package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.app.AgentContract;
import com.agentgongdan.app.ports.ai.AiAvailabilityPort;
import com.agentgongdan.app.ports.health.ReadinessPort;
import com.agentgongdan.common.api.ApiResponse;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final AiAvailabilityPort availability;
    private final ReadinessPort readiness;

    public HealthController(AiAvailabilityPort availability, ReadinessPort readiness) {
        this.availability = availability;
        this.readiness = readiness;
    }

    @GetMapping({"/health", "/api/v1/health"})
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "service", "java-bff"
        ));
    }

    /**
     * 子 Agent 存活检查；不鉴权、不暴露数据库地址或密钥。
     */
    @GetMapping("/agent/v1/health")
    public ApiResponse<Map<String, Object>> agentHealth() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", "UP");
        data.put("service", "java-bff");
        data.put("agentId", AgentContract.AGENT_ID);
        data.put("version", AgentContract.VERSION);
        data.put("protocol", AgentContract.PROTOCOL);
        data.put("time", Instant.now().toString());
        return ApiResponse.ok(data);
    }

    /**
     * 子 Agent 就绪检查：数据库连接可用时 200，否则 503。
     */
    @GetMapping("/agent/v1/ready")
    public ResponseEntity<ApiResponse<Map<String, Object>>> agentReady() {
        boolean databaseReady = readiness.databaseReady();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", databaseReady ? "UP" : "DOWN");
        data.put("service", "java-bff");
        data.put("agentId", AgentContract.AGENT_ID);
        data.put("version", AgentContract.VERSION);
        data.put("ready", databaseReady);
        data.put("checks", Map.of("database", databaseReady ? "UP" : "DOWN"));
        data.put("time", Instant.now().toString());
        if (databaseReady) {
            return ResponseEntity.ok(ApiResponse.ok(data));
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.error(ErrorCode.SYS_DEPENDENCY_UNAVAILABLE,
                        "依赖不可用", data));
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
