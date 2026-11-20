package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.app.ports.agent.AgentTaskQueuePort;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 启动恢复（轻量生产适配）：
 * 1. PENDING 任务重新入队。Worker 以条件更新原子领取，多实例重复入队是安全的；
 * 2. 超过 staleSeconds 仍处于 RUNNING 的任务标记 FAILED，避免进程崩溃后永久悬挂。
 *
 * 不重放 RUNNING：写能力可能已产生部分副作用，自动重放可能重复建单/派单。
 * 多实例部署时 staleSeconds 必须大于正常任务最长执行时间，或关闭本恢复器。
 */
@Component
public class AgentTaskRecoveryRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskRecoveryRunner.class);
    private static final long TENANT = 0L;
    private static final int MAX_PAGES = 10;
    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String FAILED = "FAILED";
    private static final String INTERRUPTED_MESSAGE =
            "执行实例重启或任务超时，任务已中断；请使用相同 clientTaskId 重试";

    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventService eventService;
    private final AgentTaskQueuePort taskQueue;
    private final MetricsPort metrics;
    private final boolean enabled;
    private final int staleSeconds;
    private final int batchSize;

    public AgentTaskRecoveryRunner(AgentTaskMapper taskMapper,
                                   AgentTaskEventService eventService,
                                   AgentTaskQueuePort taskQueue,
                                   MetricsPort metrics,
                                   @Value("${agent.task.recovery.enabled:true}") boolean enabled,
                                   @Value("${agent.task.recovery.stale-seconds:300}")
                                   int staleSeconds,
                                   @Value("${agent.task.recovery.batch-size:100}") int batchSize) {
        this.taskMapper = taskMapper;
        this.eventService = eventService;
        this.taskQueue = taskQueue;
        this.metrics = metrics;
        this.enabled = enabled;
        this.staleSeconds = Math.max(60, staleSeconds);
        this.batchSize = Math.max(1, batchSize);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        if (!enabled) {
            return;
        }
        int requeued = requeuePending();
        int failed = failStaleRunning();
        if (requeued > 0 || failed > 0) {
            log.info("agent.task.recovery requeued={} staleMarkedFailed={}", requeued, failed);
        }
    }

    int requeuePending() {
        int requeued = 0;
        Set<String> seen = new HashSet<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            List<AgentTaskEntity> pending = selectPending(seen);
            if (pending.isEmpty()) {
                break;
            }
            for (AgentTaskEntity entity : pending) {
                seen.add(entity.getTaskId());
                try {
                    taskQueue.enqueue(entity.getTaskId());
                    requeued++;
                } catch (RuntimeException ex) {
                    markFailed(entity, "任务队列不可用，恢复入队失败", PENDING);
                }
            }
            if (pending.size() < batchSize) {
                break;
            }
        }
        return requeued;
    }

    int failStaleRunning() {
        int failed = 0;
        LocalDateTime threshold = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(staleSeconds);
        for (int page = 0; page < MAX_PAGES; page++) {
            List<AgentTaskEntity> stale = selectStaleRunning(threshold);
            if (stale.isEmpty()) {
                break;
            }
            for (AgentTaskEntity entity : stale) {
                if (markFailed(entity, INTERRUPTED_MESSAGE, RUNNING)) {
                    failed++;
                }
            }
            if (stale.size() < batchSize) {
                break;
            }
        }
        return failed;
    }

    private List<AgentTaskEntity> selectPending(Set<String> excluded) {
        List<String> ids = excluded.isEmpty() ? List.of() : new ArrayList<>(excluded);
        return taskMapper.selectList(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getStatus, PENDING)
                .notIn(!ids.isEmpty(), AgentTaskEntity::getTaskId, ids)
                .orderByAsc(AgentTaskEntity::getCreatedAt)
                .last("LIMIT " + batchSize));
    }

    private List<AgentTaskEntity> selectStaleRunning(LocalDateTime threshold) {
        return taskMapper.selectList(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getStatus, RUNNING)
                .lt(AgentTaskEntity::getUpdatedAt, threshold)
                .orderByAsc(AgentTaskEntity::getUpdatedAt)
                .last("LIMIT " + batchSize));
    }

    private boolean markFailed(AgentTaskEntity entity, String message, String... expectedStatuses) {
        int updated = taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, entity.getTaskId())
                .in(AgentTaskEntity::getStatus, List.of(expectedStatuses))
                .set(AgentTaskEntity::getStatus, FAILED)
                .set(AgentTaskEntity::getErrorCode,
                        String.valueOf(ErrorCode.SYS_DEPENDENCY_UNAVAILABLE.getCode()))
                .set(AgentTaskEntity::getErrorMessage, message)
                .set(AgentTaskEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            return false;
        }
        eventService.record(entity.getTaskId(), FAILED, message);
        metrics.taskStatus(entity.getCapability(), FAILED);
        return true;
    }
}
