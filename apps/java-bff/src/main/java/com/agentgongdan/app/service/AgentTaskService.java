package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.adapters.web.dto.AgentTaskEventView;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.app.dto.AgentTaskEvent;
import com.agentgongdan.app.ports.agent.AgentTaskQueuePort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 子 Agent Task API 应用服务。
 * 职责边界：幂等、确认、状态机、事件与队列调度；
 * 能力执行在 AgentTaskWorker，具体业务在平台既有用例，不在此层直连数据库以外的适配器。
 */
@Service
public class AgentTaskService {

    private static final String AGENT_ID = "property-maintenance";
    private static final long TENANT = 0L;
    private static final long CONFIRM_TTL_MINUTES = 10;
    private static final String MODE_SYNC = "SYNC";
    private static final String MODE_ASYNC = "ASYNC";

    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventService eventService;
    private final AgentTaskWorker taskWorker;
    private final AgentTaskQueuePort taskQueue;
    private final ObjectMapper objectMapper;

    public AgentTaskService(AgentTaskMapper taskMapper,
                            AgentTaskEventService eventService,
                            AgentTaskWorker taskWorker,
                            AgentTaskQueuePort taskQueue,
                            ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.eventService = eventService;
        this.taskWorker = taskWorker;
        this.taskQueue = taskQueue;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", AGENT_ID);
        manifest.put("name", "物业报修与工单 Agent");
        manifest.put("version", "0.5.0");
        manifest.put("protocol", "agent-task-v1");
        manifest.put("taskEndpoint", "/agent/v1/tasks");
        manifest.put("eventStreamEndpoint", "/agent/v1/tasks/{taskId}/events/stream");
        manifest.put("executionModes", List.of(MODE_SYNC, MODE_ASYNC));
        manifest.put("auth", Map.of(
                "type", "internal-token",
                "header", "X-Internal-Token",
                "delegationHeader", "X-Delegation-Token"));
        manifest.put("capabilities", List.of(
                capability("ticket.query", "查询当前 Actor 可见工单", "ticket:read", false, "READ_ONLY"),
                capability("ticket.detail", "按工单 ID 或工单号查询详情", "ticket:read", false, "READ_ONLY"),
                capability("ticket.similar", "按描述检索相似历史工单", "ticket:read", false, "READ_ONLY"),
                capability("knowledge.search", "检索知识库片段", "knowledge:read", false, "READ_ONLY"),
                capability("ticket.create", "创建工单草稿，确认后落库", "ticket:create", true, "WRITE"),
                capability("ticket.export", "导出工单 Excel，确认后生成", "ticket:read", true, "WRITE"),
                capability("ticket.assign", "派单", "ticket:assign", true, "WRITE"),
                capability("ticket.accept", "接单", "ticket:handle", true, "WRITE"),
                capability("ticket.resolve", "提交解决", "ticket:handle", true, "WRITE"),
                capability("ticket.verify", "验证", "ticket:verify", true, "WRITE"),
                capability("ticket.close", "关闭", "ticket:close", true, "WRITE"),
                capability("ticket.cancel", "取消", "ticket:close", true, "WRITE"),
                capability("ticket.hold", "挂起", "ticket:handle", true, "WRITE"),
                capability("ticket.resume", "恢复", "ticket:handle", true, "WRITE"),
                capability("ticket.finish", "结束", "ticket:close", true, "WRITE"),
                capability("ticket.comment.add", "添加评论", "ticket:read", true, "WRITE"),
                capability("ticket.material.add", "添加使用材料", "ticket:handle", true, "WRITE"),
                capability("ticket.visit.add", "登记回访", "ticket:close", true, "WRITE")
        ));
        return manifest;
    }

    public Map<String, Object> agentCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "Property Maintenance Agent");
        card.put("description", "物业报修、工单流转、现场处理、知识检索与导出子 Agent");
        card.put("url", "http://127.0.0.1:8200/agent/v1");
        card.put("version", "0.5.0");
        card.put("protocolVersion", "agent-task-v1");
        card.put("capabilities", Map.of(
                "streaming", true,
                "pushNotifications", false,
                "stateTransitionHistory", true
        ));
        card.put("defaultInputModes", List.of("application/json"));
        card.put("defaultOutputModes", List.of("application/json", "text/event-stream"));
        card.put("skills", manifest().get("capabilities"));
        card.put("securitySchemes", Map.of(
                "internalToken", Map.of("type", "apiKey", "in", "header", "name", "X-Internal-Token"),
                "delegationToken", Map.of("type", "apiKey", "in", "header", "name", "X-Delegation-Token")
        ));
        return card;
    }

    public AgentTaskView submit(AgentTaskRequest request, long actorId, List<String> roles,
                                List<String> delegationScopes, String delegationId, String traceId) {
        if (request == null || !StringUtils.hasText(request.taskType())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "taskType 不能为空");
        }
        String capability = request.taskType().trim();
        requireScope(capability, delegationScopes);

        String clientTaskId = StringUtils.hasText(request.clientTaskId())
                ? request.clientTaskId().trim() : null;
        if (clientTaskId != null) {
            AgentTaskEntity existing = findExisting(actorId, clientTaskId);
            if (existing != null) {
                return toView(existing);
            }
        }

        String executionMode = normalizeMode(request.executionMode());
        String taskId = UUID.randomUUID().toString();
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setTaskId(taskId);
        entity.setAgentId(AGENT_ID);
        entity.setCapability(capability);
        entity.setActorId(actorId);
        entity.setActorRoles(String.join(",", roles == null ? List.of() : roles));
        entity.setClientTaskId(clientTaskId);
        entity.setDelegationId(delegationId);
        entity.setInputJson(toJson(request.input() == null ? Map.of() : request.input()));
        entity.setTraceId(traceId);
        entity.setExecutionMode(executionMode);
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getCreatedAt());

        boolean requiresConfirm = isWriteCapability(capability);
        if (requiresConfirm) {
            entity.setStatus("WAITING_CONFIRM");
            entity.setConfirmationToken(UUID.randomUUID().toString().replace("-", ""));
            entity.setConfirmationExpiresAt(LocalDateTime.now(ZoneOffset.UTC)
                    .plusMinutes(CONFIRM_TTL_MINUTES));
            entity.setResultJson(toJson(Map.of(
                    "summary", "该能力需要人工确认后才会执行",
                    "capability", capability)));
        } else if (MODE_ASYNC.equals(executionMode)) {
            entity.setStatus("PENDING");
            entity.setResultJson(toJson(Map.of(
                    "summary", "任务已进入队列",
                    "capability", capability)));
        } else {
            taskWorker.executeInline(entity, roles);
        }

        try {
            taskMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            AgentTaskEntity existing = findExisting(actorId, clientTaskId);
            if (existing != null) {
                return toView(existing);
            }
            throw e;
        }
        eventService.record(taskId, entity.getStatus(), entity.getErrorMessage());

        if (!requiresConfirm && MODE_ASYNC.equals(executionMode)) {
            enqueue(taskId, entity);
        }
        return toView(entity);
    }

    public AgentTaskView get(String taskId, long actorId) {
        AgentTaskEntity entity = find(taskId);
        requireOwner(entity, actorId, "无权访问该任务");
        return toView(entity);
    }

    public AgentTaskView cancel(String taskId, long actorId) {
        AgentTaskEntity entity = find(taskId);
        requireOwner(entity, actorId, "无权取消该任务");
        if (!List.of("PENDING", "RUNNING", "WAITING_CONFIRM").contains(entity.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "当前任务不可取消");
        }
        int updated = taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, taskId)
                .in(AgentTaskEntity::getStatus, List.of("PENDING", "RUNNING", "WAITING_CONFIRM"))
                .set(AgentTaskEntity::getStatus, "CANCELLED")
                .set(AgentTaskEntity::getErrorCode, null)
                .set(AgentTaskEntity::getErrorMessage, "调用方已取消")
                .set(AgentTaskEntity::getConfirmationToken, null)
                .set(AgentTaskEntity::getConfirmationExpiresAt, null)
                .set(AgentTaskEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "当前任务不可取消");
        }
        eventService.record(taskId, "CANCELLED", "调用方已取消");
        return toView(find(taskId));
    }

    public List<AgentTaskEventView> events(String taskId, long actorId) {
        return eventsAfter(taskId, actorId, null).stream()
                .map(this::toEventView)
                .toList();
    }

    public List<AgentTaskEvent> eventsAfter(String taskId, long actorId, String afterEventId) {
        AgentTaskEntity task = find(taskId);
        requireOwner(task, actorId, "无权访问该任务");
        return eventService.listAfter(taskId, afterEventId);
    }

    public AgentTaskView confirm(String taskId, String confirmationToken,
                                 long actorId, List<String> roles) {
        AgentTaskEntity entity = find(taskId);
        requireOwner(entity, actorId, "无权确认该任务");
        if (!"WAITING_CONFIRM".equals(entity.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "该任务不需要确认");
        }
        if (!StringUtils.hasText(entity.getConfirmationToken())
                || !entity.getConfirmationToken().equals(confirmationToken)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "确认令牌无效");
        }
        if (entity.getConfirmationExpiresAt() == null
                || entity.getConfirmationExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            entity.setStatus("FAILED");
            entity.setErrorCode(String.valueOf(ErrorCode.AUTH_TOKEN_EXPIRED.getCode()));
            entity.setErrorMessage("确认已过期");
            entity.setConfirmationToken(null);
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(entity);
            eventService.record(taskId, "FAILED", "确认已过期");
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "确认已过期");
        }

        entity.setConfirmedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setConfirmationToken(null);
        entity.setConfirmationExpiresAt(null);
        if (MODE_ASYNC.equals(entity.getExecutionMode())) {
            entity.setStatus("PENDING");
            entity.setResultJson(toJson(Map.of(
                    "summary", "已确认，任务进入队列",
                    "capability", entity.getCapability())));
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.updateById(entity);
            eventService.record(taskId, "PENDING", "已确认，任务进入队列");
            enqueue(taskId, entity);
            return toView(entity);
        }

        taskWorker.executeInline(entity, roles);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(entity);
        eventService.record(taskId, entity.getStatus(), entity.getErrorMessage());
        return toView(entity);
    }

    private void enqueue(String taskId, AgentTaskEntity entity) {
        try {
            taskQueue.enqueue(taskId);
        } catch (RuntimeException ex) {
            entity.setStatus("FAILED");
            entity.setErrorCode(String.valueOf(ErrorCode.SYS_DEPENDENCY_UNAVAILABLE.getCode()));
            entity.setErrorMessage("任务队列不可用");
            entity.setResultJson(toJson(Map.of(
                    "summary", "任务队列不可用",
                    "capability", entity.getCapability())));
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
            taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                    .eq(AgentTaskEntity::getTenantId, TENANT)
                    .eq(AgentTaskEntity::getTaskId, taskId)
                    .eq(AgentTaskEntity::getStatus, "PENDING")
                    .set(AgentTaskEntity::getStatus, "FAILED")
                    .set(AgentTaskEntity::getResultJson, entity.getResultJson())
                    .set(AgentTaskEntity::getErrorCode, entity.getErrorCode())
                    .set(AgentTaskEntity::getErrorMessage, entity.getErrorMessage())
                    .set(AgentTaskEntity::getUpdatedAt, entity.getUpdatedAt()));
            eventService.record(taskId, "FAILED", entity.getErrorMessage());
        }
    }

    private AgentTaskEntity findExisting(long actorId, String clientTaskId) {
        if (clientTaskId == null) {
            return null;
        }
        return taskMapper.selectOne(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getActorId, actorId)
                .eq(AgentTaskEntity::getClientTaskId, clientTaskId)
                .last("LIMIT 1"));
    }

    private AgentTaskEntity find(String taskId) {
        AgentTaskEntity entity = taskMapper.selectOne(Wrappers.<AgentTaskEntity>lambdaQuery()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, taskId)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND, "任务不存在");
        }
        return entity;
    }

    private void requireOwner(AgentTaskEntity entity, long actorId, String message) {
        if (!entity.getActorId().equals(actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, message);
        }
    }

    private void requireScope(String capability, List<String> scopes) {
        if (scopes == null) {
            return;
        }
        if (!(scopes.contains("*") || scopes.contains(capability))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "委托令牌 scope 不允许该能力");
        }
    }

    private String normalizeMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            return MODE_SYNC;
        }
        String normalized = mode.trim().toUpperCase();
        if (!List.of(MODE_SYNC, MODE_ASYNC).contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "executionMode 只支持 SYNC/ASYNC");
        }
        return normalized;
    }

    private AgentTaskView toView(AgentTaskEntity entity) {
        Object result = null;
        if (StringUtils.hasText(entity.getResultJson())) {
            try {
                result = objectMapper.readValue(entity.getResultJson(), Object.class);
            } catch (Exception ignored) {
                result = entity.getResultJson();
            }
        }
        return new AgentTaskView(
                entity.getTaskId(),
                entity.getAgentId(),
                entity.getCapability(),
                entity.getStatus(),
                result,
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getLatencyMs() == null ? 0 : entity.getLatencyMs(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getCostMicros(),
                entity.getExecutionMode() == null ? MODE_SYNC : entity.getExecutionMode(),
                entity.getDelegationId(),
                entity.getConfirmationToken(),
                entity.getConfirmationExpiresAt() == null ? null
                        : entity.getConfirmationExpiresAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                entity.getCreatedAt() == null ? null
                        : entity.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }

    private AgentTaskEventView toEventView(AgentTaskEvent event) {
        return new AgentTaskEventView(event.id(), event.taskId(), event.status(),
                event.message(), event.createdAt());
    }

    private boolean isWriteCapability(String capability) {
        return List.of("ticket.create", "ticket.export", "ticket.assign", "ticket.accept",
                        "ticket.resolve", "ticket.verify", "ticket.close", "ticket.cancel",
                        "ticket.hold", "ticket.resume", "ticket.finish", "ticket.comment.add",
                        "ticket.material.add", "ticket.visit.add")
                .contains(capability);
    }

    private Map<String, Object> capability(String name, String description, String permission,
                                           boolean requiresConfirm, String mode) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("description", description);
        item.put("permission", permission);
        item.put("requiresConfirm", requiresConfirm);
        item.put("mode", mode);
        return item;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
