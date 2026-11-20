package com.agentgongdan.app.ports.agent;

import java.util.Map;

/**
 * 下游 Agent 的标准返回（预留），与 Task API 的响应语义保持一致。
 */
public record AgentInvocationResult(
        String taskId,
        String status,
        Map<String, Object> result,
        String errorCode,
        String errorMessage
) {
}
