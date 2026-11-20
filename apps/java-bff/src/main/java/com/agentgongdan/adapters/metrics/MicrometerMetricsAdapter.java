package com.agentgongdan.adapters.metrics;

import com.agentgongdan.app.ports.metrics.MetricsPort;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Micrometer 指标适配器。
 * 通过 Spring Boot Actuator 的 /actuator/metrics 暴露；未来接入 Prometheus 只需增加 registry 依赖。
 */
@Component
public class MicrometerMetricsAdapter implements MetricsPort {

    private static final String UNKNOWN = "unknown";

    private final MeterRegistry registry;

    public MicrometerMetricsAdapter(
            MeterRegistry registry,
            @Qualifier("agentTaskExecutor") ThreadPoolTaskExecutor agentTaskExecutor) {
        this.registry = registry;
        Gauge.builder("agent_task_queue_depth", () -> queueSize(agentTaskExecutor))
                .description("子 Agent 任务队列积压数")
                .register(registry);
        Gauge.builder("agent_task_active_threads", () -> activeThreads(agentTaskExecutor))
                .description("子 Agent 任务执行线程数")
                .register(registry);
    }

    @Override
    public void taskStatus(String capability, String status) {
        Counter.builder("agent_task_total")
                .description("子 Agent 任务状态流转次数")
                .tag("capability", tag(capability))
                .tag("status", tag(status))
                .register(registry)
                .increment();
    }

    @Override
    public void taskLatency(String capability, long latencyMs) {
        if (latencyMs < 0) {
            return;
        }
        Timer.builder("agent_task_latency_seconds")
                .description("子 Agent 任务执行耗时")
                .tag("capability", tag(capability))
                .publishPercentileHistogram()
                .register(registry)
                .record(Duration.ofMillis(latencyMs));
    }

    @Override
    public void chatCompleted(int inputTokens, int outputTokens, long latencyMs, boolean degraded) {
        Counter.builder("chat_messages_total")
                .description("AI 对话完成次数")
                .tag("outcome", degraded ? "degraded" : "ok")
                .register(registry)
                .increment();
        if (inputTokens > 0) {
            Counter.builder("chat_tokens_total")
                    .description("AI 对话 token 用量")
                    .tag("direction", "input")
                    .register(registry)
                    .increment(inputTokens);
        }
        if (outputTokens > 0) {
            Counter.builder("chat_tokens_total")
                    .description("AI 对话 token 用量")
                    .tag("direction", "output")
                    .register(registry)
                    .increment(outputTokens);
        }
        if (latencyMs >= 0) {
            Timer.builder("chat_latency_seconds")
                    .description("AI 对话首包到完成耗时")
                    .publishPercentileHistogram()
                    .register(registry)
                    .record(Duration.ofMillis(latencyMs));
        }
    }

    @Override
    public void chatFailed(String errorCode) {
        Counter.builder("chat_messages_total")
                .description("AI 对话完成次数")
                .tag("outcome", "failed")
                .tag("error", tag(errorCode))
                .register(registry)
                .increment();
    }

    @Override
    public void toolCall(String tool, String status) {
        Counter.builder("agent_tool_calls_total")
                .description("AI 工具调用次数")
                .tag("tool", tag(tool))
                .tag("status", tag(status))
                .register(registry)
                .increment();
    }

    private double queueSize(ThreadPoolTaskExecutor taskExecutor) {
        ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
        return executor == null ? 0d : executor.getQueue().size();
    }

    private double activeThreads(ThreadPoolTaskExecutor taskExecutor) {
        ThreadPoolExecutor executor = taskExecutor.getThreadPoolExecutor();
        return executor == null ? 0d : executor.getActiveCount();
    }

    private String tag(String value) {
        return value == null || value.isBlank() ? UNKNOWN : value.trim();
    }
}
