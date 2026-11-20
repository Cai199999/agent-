package com.agentgongdan.adapters.stream;

import com.agentgongdan.app.dto.AgentTaskEvent;
import com.agentgongdan.app.ports.agent.AgentTaskEventStreamPort;
import com.agentgongdan.app.service.AgentTaskEventService;
import com.agentgongdan.app.service.AgentTaskService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 基于数据库事件表的轮询订阅适配器。
 * 多实例安全：不依赖进程内事件总线，任何实例都可读取同一任务事件。
 * 升级点：替换为 Redis Pub/Sub / SSE 网关，端口契约不变。
 */
@Component
public class DbPollingAgentTaskEventStreamAdapter implements AgentTaskEventStreamPort {

    private final AgentTaskService agentTaskService;
    private final TaskScheduler taskScheduler;
    private final long pollIntervalMs;
    private final long heartbeatIntervalMs;

    public DbPollingAgentTaskEventStreamAdapter(
            AgentTaskService agentTaskService,
            @Qualifier("agentTaskScheduler") TaskScheduler taskScheduler,
            @Value("${agent.task.stream.poll-interval-ms:1000}") long pollIntervalMs,
            @Value("${agent.task.stream.heartbeat-interval-ms:15000}") long heartbeatIntervalMs) {
        this.agentTaskService = agentTaskService;
        this.taskScheduler = taskScheduler;
        this.pollIntervalMs = Math.max(200, pollIntervalMs);
        this.heartbeatIntervalMs = Math.max(1000, heartbeatIntervalMs);
    }

    @Override
    public Subscription watch(String taskId, long actorId, Long afterSeq,
                              Consumer<AgentTaskEvent> onEvent,
                              Runnable onHeartbeat,
                              Runnable onComplete,
                              Consumer<Exception> onError) {
        AtomicReference<Long> cursor = new AtomicReference<>(afterSeq);
        AtomicBoolean closed = new AtomicBoolean(false);
        AtomicLong lastHeartbeat = new AtomicLong(System.currentTimeMillis());
        AtomicReference<ScheduledFuture<?>> futureRef = new AtomicReference<>();

        Runnable poll = () -> {
            if (closed.get()) {
                return;
            }
            try {
                // 每次轮询都做归属校验，避免任务转移/伪造 taskId 读取他人事件。
                List<AgentTaskEvent> events = agentTaskService.eventsAfter(
                        taskId, actorId, cursor.get());
                for (AgentTaskEvent event : events) {
                    onEvent.accept(event);
                    cursor.set(event.seq());
                    if (AgentTaskEventService.isTerminal(event.status())) {
                        finish(futureRef, closed, onComplete);
                        return;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastHeartbeat.get() >= heartbeatIntervalMs) {
                    lastHeartbeat.set(now);
                    onHeartbeat.run();
                }
            } catch (Exception e) {
                if (closed.compareAndSet(false, true)) {
                    ScheduledFuture<?> future = futureRef.get();
                    if (future != null) {
                        future.cancel(false);
                    }
                    onError.accept(e);
                }
            }
        };

        ScheduledFuture<?> future = taskScheduler.scheduleWithFixedDelay(
                poll, Duration.ofMillis(pollIntervalMs));
        futureRef.set(future);
        return () -> {
            if (closed.compareAndSet(false, true)) {
                future.cancel(false);
            }
        };
    }

    private void finish(AtomicReference<ScheduledFuture<?>> futureRef,
                        AtomicBoolean closed, Runnable onComplete) {
        if (closed.compareAndSet(false, true)) {
            ScheduledFuture<?> future = futureRef.get();
            if (future != null) {
                future.cancel(false);
            }
            onComplete.run();
        }
    }

}
