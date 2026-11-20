package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.app.service.AgentMetricsService;
import com.agentgongdan.common.api.ApiResponse;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/**
 * 子 Agent 可观测性聚合端点。
 * 仅内部令牌 + 管理员可访问；返回成功率、P95、token 与工具调用分布。
 */
@RestController
@RequestMapping("/agent/v1")
public class AgentMetricsController {

    private final AgentMetricsService metricsService;
    private final int maxRangeDays;

    public AgentMetricsController(AgentMetricsService metricsService,
                                  @Value("${agent.metrics.max-range-days:90}") int maxRangeDays) {
        this.metricsService = metricsService;
        this.maxRangeDays = Math.max(1, maxRangeDays);
    }

    @GetMapping("/metrics/summary")
    public ApiResponse<Map<String, Object>> summary(
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestAttribute("internalActorRoles") List<String> roles) {
        if (roles == null || !roles.contains("ADMIN")) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "需要管理员权限");
        }
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime fromTime = parse(from, now.minusDays(7));
        LocalDateTime toTime = parse(to, now);
        if (fromTime.isAfter(toTime)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "from 不能晚于 to");
        }
        if (fromTime.isBefore(toTime.minusDays(maxRangeDays))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "查询范围不能超过 " + maxRangeDays + " 天");
        }
        return ApiResponse.ok(metricsService.summary(fromTime, toTime));
    }

    private LocalDateTime parse(String value, LocalDateTime fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value.trim())
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value.trim());
            } catch (Exception e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "时间格式必须是 ISO 8601，例如 2026-11-19T00:00:00+08:00");
            }
        }
    }
}
