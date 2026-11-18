package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.app.ports.agent.AgentTaskJobHandler;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Agent 任务执行器。
 * 同步任务在请求线程内直接执行；异步任务由队列线程调用 {@link #execute(String)}。
 * 状态迁移使用条件更新，避免取消后仍被工作线程覆盖。
 */
@Service
public class AgentTaskWorker implements AgentTaskJobHandler {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskWorker.class);
    private static final long TENANT = 0L;

    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventService eventService;
    private final AgentTaskDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    public AgentTaskWorker(AgentTaskMapper taskMapper,
                           AgentTaskEventService eventService,
                           AgentTaskDispatcher dispatcher,
                           ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.eventService = eventService;
        this.dispatcher = dispatcher;
        this.objectMapper = objectMapper;
    }

    /**
     * 异步入口：先原子领取 PENDING，再执行，最后条件落终态。
     */
    @Override
    public void execute(String taskId) {
        AgentTaskEntity entity = find(taskId);
        if (entity == null || !"PENDING".equals(entity.getStatus())) {
            return;
        }
        int claimed = taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, taskId)
                .eq(AgentTaskEntity::getStatus, "PENDING")
                .set(AgentTaskEntity::getStatus, "RUNNING")
                .set(AgentTaskEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (claimed == 0) {
            return;
        }
        entity.setStatus("RUNNING");
        eventService.record(taskId, "RUNNING", null);
        run(entity, roles(entity));

        int updated = taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, taskId)
                .eq(AgentTaskEntity::getStatus, "RUNNING")
                .set(AgentTaskEntity::getStatus, entity.getStatus())
                .set(AgentTaskEntity::getResultJson, entity.getResultJson())
                .set(AgentTaskEntity::getErrorCode, entity.getErrorCode())
                .set(AgentTaskEntity::getErrorMessage, entity.getErrorMessage())
                .set(AgentTaskEntity::getLatencyMs, entity.getLatencyMs())
                .set(AgentTaskEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            log.info("agent task {} was cancelled or claimed elsewhere, skip terminal persist", taskId);
            return;
        }
        eventService.record(taskId, entity.getStatus(), entity.getErrorMessage());
    }

    /**
     * 同步执行：只修改内存实体，由调用方负责持久化。
     */
    public void executeInline(AgentTaskEntity entity, List<String> roles) {
        run(entity, roles);
    }

    private void run(AgentTaskEntity entity, List<String> roles) {
        long started = System.currentTimeMillis();
        try {
            Object result = dispatcher.dispatch(entity.getCapability(),
                    fromJson(entity.getInputJson()), entity.getActorId(), roles, entity.getTaskId());
            entity.setStatus("SUCCEEDED");
            entity.setResultJson(toJson(result));
            entity.setErrorCode(null);
            entity.setErrorMessage(null);
        } catch (ApiException ex) {
            entity.setStatus("FAILED");
            entity.setErrorCode(String.valueOf(ex.getErrorCode().getCode()));
            entity.setErrorMessage(ex.getMessage());
        } catch (Exception ex) {
            log.error("agent task {} failed", entity.getTaskId(), ex);
            entity.setStatus("FAILED");
            entity.setErrorCode(String.valueOf(ErrorCode.SYS_INTERNAL_ERROR.getCode()));
            entity.setErrorMessage(ErrorCode.SYS_INTERNAL_ERROR.getMessage());
        }
        entity.setLatencyMs((int) (System.currentTimeMillis() - started));
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
    }

    private List<String> roles(AgentTaskEntity entity) {
        if (!StringUtils.hasText(entity.getActorRoles())) {
            return List.of();
        }
        return Arrays.stream(entity.getActorRoles().split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private AgentTaskEntity find(String taskId) {
        return taskMapper.selectOne(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, taskId)
                .last("LIMIT 1"));
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
