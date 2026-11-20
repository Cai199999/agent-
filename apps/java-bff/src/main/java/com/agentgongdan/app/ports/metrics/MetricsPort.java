package com.agentgongdan.app.ports.metrics;

/**
 * 指标端口：应用层只声明需要记录的事实，具体实现可替换为 Micrometer、OpenTelemetry 等。
 * 标签值必须是有限集合（能力、状态、工具名），禁止把 taskId / traceId 当作标签。
 */
public interface MetricsPort {

    void taskStatus(String capability, String status);

    void taskLatency(String capability, long latencyMs);

    void chatCompleted(int inputTokens, int outputTokens, long latencyMs, boolean degraded);

    void chatFailed(String errorCode);

    void toolCall(String tool, String status);
}
