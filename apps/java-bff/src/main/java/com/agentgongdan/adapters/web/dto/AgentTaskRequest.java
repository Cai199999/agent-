package com.agentgongdan.adapters.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record AgentTaskRequest(
        @NotBlank(message = "taskType 不能为空") String taskType,
        Map<String, Object> input,
        Map<String, Object> contextRefs,
        String clientTaskId,
        String executionMode,
        String parentTaskId,
        String correlationId,
        String deadlineAt,
        Map<String, Object> budget
) {
}
