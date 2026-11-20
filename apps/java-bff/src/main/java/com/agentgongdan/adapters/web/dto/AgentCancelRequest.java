package com.agentgongdan.adapters.web.dto;

/**
 * 取消任务请求；reason 可选，用于审计与主 Agent 恢复判断。
 */
public record AgentCancelRequest(
        String reason
) {
}
