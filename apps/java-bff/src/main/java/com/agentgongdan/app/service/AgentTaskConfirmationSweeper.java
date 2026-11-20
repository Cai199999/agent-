package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 确认令牌过期清理：避免任务长期停在 WAITING_CONFIRM。
 * 多实例安全：条件更新只允许一次状态迁移，重复扫描不会重复处理。
 */
@Component
@ConditionalOnProperty(name = "agent.task.confirmation.sweep-enabled",
        havingValue = "true", matchIfMissing = true)
public class AgentTaskConfirmationSweeper {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskConfirmationSweeper.class);
    private static final long TENANT = 0L;
    private static final String WAITING_CONFIRM = "WAITING_CONFIRM";
    private static final String FAILED = "FAILED";
    private static final String EXPIRED_MESSAGE = "确认已过期";

    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventService eventService;
    private final MetricsPort metrics;
    private final int batchSize;

    public AgentTaskConfirmationSweeper(
            AgentTaskMapper taskMapper,
            AgentTaskEventService eventService,
            MetricsPort metrics,
            @Value("${agent.task.confirmation.sweep-batch-size:100}") int batchSize) {
        this.taskMapper = taskMapper;
        this.eventService = eventService;
        this.metrics = metrics;
        this.batchSize = Math.max(1, batchSize);
    }

    @Scheduled(fixedDelayString = "${agent.task.confirmation.sweep-interval-ms:60000}")
    public void sweep() {
        int expired = expireOverdue();
        if (expired > 0) {
            log.info("agent.task.confirmation expired={}", expired);
        }
    }

    int expireOverdue() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<AgentTaskEntity> overdue = taskMapper.selectList(
                Wrappers.<AgentTaskEntity>lambdaQuery()
                        .eq(AgentTaskEntity::getTenantId, TENANT)
                        .eq(AgentTaskEntity::getStatus, WAITING_CONFIRM)
                        .isNotNull(AgentTaskEntity::getConfirmationExpiresAt)
                        .lt(AgentTaskEntity::getConfirmationExpiresAt, now)
                        .orderByAsc(AgentTaskEntity::getConfirmationExpiresAt)
                        .last("LIMIT " + batchSize));
        int expired = 0;
        for (AgentTaskEntity entity : overdue) {
            int updated = taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                    .eq(AgentTaskEntity::getTenantId, TENANT)
                    .eq(AgentTaskEntity::getTaskId, entity.getTaskId())
                    .eq(AgentTaskEntity::getStatus, WAITING_CONFIRM)
                    .lt(AgentTaskEntity::getConfirmationExpiresAt, now)
                    .set(AgentTaskEntity::getStatus, FAILED)
                    .set(AgentTaskEntity::getErrorCode,
                            String.valueOf(ErrorCode.AUTH_TOKEN_EXPIRED.getCode()))
                    .set(AgentTaskEntity::getErrorMessage, EXPIRED_MESSAGE)
                    .set(AgentTaskEntity::getConfirmationToken, null)
                    .set(AgentTaskEntity::getConfirmationExpiresAt, null)
                    .set(AgentTaskEntity::getUpdatedAt, now));
            if (updated == 0) {
                continue;
            }
            eventService.record(entity.getTaskId(), "TASK_CONFIRM_EXPIRED", FAILED,
                    EXPIRED_MESSAGE);
            metrics.taskStatus(entity.getCapability(), FAILED);
            expired++;
        }
        return expired;
    }
}
