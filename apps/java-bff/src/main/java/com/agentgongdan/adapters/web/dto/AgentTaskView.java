package com.agentgongdan.adapters.web.dto;

import java.util.Map;

public record AgentTaskView(
        String taskId,
        String agentId,
        String capability,
        String capabilityId,
        String status,
        Object result,
        String errorCode,
        String errorMessage,
        String traceId,
        long latencyMs,
        Integer inputTokens,
        Integer outputTokens,
        Long costMicros,
        Map<String, Object> usage,
        String executionMode,
        String delegationId,
        String confirmationToken,
        String confirmationExpiresAt,
        String createdAt,
        String parentTaskId,
        String correlationId,
        String deadlineAt,
        Map<String, Object> contextRefs,
        Map<String, Object> budget,
        String clientTaskId,
        int progress,
        String currentStep,
        String cancelReason
) {
}
