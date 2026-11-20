package com.agentgongdan.app.ports.agent;

import java.util.List;
import java.util.Map;

/**
 * 调用下游 Agent 的标准入参（预留）。
 * 安全约束：只传引用与最小上下文；不得转发内部令牌或完整聊天历史。
 */
public record AgentInvocation(
        String targetAgentId,
        String taskType,
        Map<String, Object> input,
        Map<String, Object> contextRefs,
        long actorId,
        List<String> roles,
        List<String> scopes,
        String traceId,
        String parentTaskId,
        String correlationId,
        String deadlineAt,
        Map<String, Object> budget
) {
}
