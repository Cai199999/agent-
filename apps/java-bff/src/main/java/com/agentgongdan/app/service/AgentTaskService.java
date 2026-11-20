package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.adapters.web.dto.AgentTaskEventView;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.app.AgentContract;
import com.agentgongdan.app.dto.AgentTaskEvent;
import com.agentgongdan.app.ports.agent.AgentTaskQueuePort;
import com.agentgongdan.app.ports.metrics.MetricsPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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

    private static final long TENANT = 0L;
    private static final long CONFIRM_TTL_MINUTES = 10;
    private static final String MODE_SYNC = "SYNC";
    private static final String MODE_ASYNC = "ASYNC";
    private static final String CAPABILITY_PREFIX = "property.";

    private static final int MAX_CONTEXT_REFS = 50;
    private static final int MAX_REF_ID_LENGTH = 64;
    private static final int MAX_CANCEL_REASON_LENGTH = 200;

    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventService eventService;
    private final AgentTaskWorker taskWorker;
    private final AgentTaskQueuePort taskQueue;
    private final ObjectMapper objectMapper;
    private final MetricsPort metrics;
    private final boolean downstreamEnabled;
    private final boolean deadlineEnabled;
    private final String cardBaseUrl;
    private final int maxInputBytes;

    public AgentTaskService(AgentTaskMapper taskMapper,
                            AgentTaskEventService eventService,
                            AgentTaskWorker taskWorker,
                            AgentTaskQueuePort taskQueue,
                            ObjectMapper objectMapper,
                            MetricsPort metrics,
                            @Value("${agent.downstream.enabled:false}") boolean downstreamEnabled,
                            @Value("${agent.task.deadline.enabled:true}") boolean deadlineEnabled,
                            @Value("${agent.card.base-url:http://127.0.0.1:8200/agent/v1}")
                            String cardBaseUrl,
                            @Value("${agent.task.limits.max-input-bytes:65536}") int maxInputBytes) {
        this.taskMapper = taskMapper;
        this.eventService = eventService;
        this.taskWorker = taskWorker;
        this.taskQueue = taskQueue;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
        this.downstreamEnabled = downstreamEnabled;
        this.deadlineEnabled = deadlineEnabled;
        this.cardBaseUrl = cardBaseUrl;
        this.maxInputBytes = maxInputBytes;
    }

    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", AgentContract.AGENT_ID);
        manifest.put("name", "物业报修与工单 Agent");
        manifest.put("version", AgentContract.VERSION);
        manifest.put("protocol", AgentContract.PROTOCOL);
        manifest.put("taskEndpoint", "/agent/v1/tasks");
        manifest.put("eventStreamEndpoint", "/agent/v1/tasks/{taskId}/events/stream");
        manifest.put("executionModes", List.of(MODE_SYNC, MODE_ASYNC));
        manifest.put("auth", Map.of(
                "type", "internal-token",
                "header", "X-Internal-Token",
                "delegationHeader", "X-Delegation-Token"));
        manifest.put("features", props(
                "streaming", true,
                "async", true,
                "confirmation", true,
                "confirmationDecision", true,
                "confirmationExpirySweep", true,
                "cancellation", true,
                "cancellationReason", true,
                "idempotency", true,
                "clientTaskLookup", true,
                "delegation", true,
                "eventReplay", true,
                "lastEventId", true,
                "progress", true,
                "attachments", true));
        manifest.put("endpoints", props(
                "manifest", "/agent/v1/manifest",
                "card", "/agent/v1/card",
                "wellKnownCard", "/.well-known/agent-card.json",
                "tasks", "/agent/v1/tasks",
                "task", "/agent/v1/tasks/{taskId}",
                "taskByClientTaskId", "/agent/v1/tasks?clientTaskId={clientTaskId}",
                "confirm", "/agent/v1/tasks/{taskId}/confirm",
                "cancel", "/agent/v1/tasks/{taskId}/cancel",
                "events", "/agent/v1/tasks/{taskId}/events",
                "stream", "/agent/v1/tasks/{taskId}/events/stream",
                "attachments", "/agent/v1/tickets/{ticketId}/attachments",
                "delegation", "/agent/v1/delegation/token",
                "metrics", "/agent/v1/metrics/summary",
                "health", "/agent/v1/health",
                "ready", "/agent/v1/ready"));
        manifest.put("healthEndpoint", "/agent/v1/health");
        manifest.put("readyEndpoint", "/agent/v1/ready");
        manifest.put("delegationEndpoint", "/agent/v1/delegation/token");
        manifest.put("envelope", props(
                "codeField", "code",
                "successCode", 0,
                "messageField", "message",
                "dataField", "data",
                "traceIdField", "traceId",
                "taskStatuses", List.of("PENDING", "RUNNING", "WAITING_CONFIRM",
                        "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED"),
                "errorCodes", Map.of(
                        "10001", "鉴权失败",
                        "10002", "令牌过期",
                        "10003", "越权/scope 拒绝",
                        "20001", "任务不存在",
                        "20002", "状态冲突",
                        "20005", "超过截止时间",
                        "50001", "系统内部错误",
                        "50004", "依赖不可用",
                        "60001", "参数校验失败")));
        manifest.put("contextSchema", props(
                "parentTaskId", "上游任务 ID（可选）",
                "correlationId", "调用链关联 ID（可选）",
                "deadlineAt", "期望完成时间 ISO 8601（可选；执行前检查，已过期不执行）",
                "budget", "调用预算对象（可选；仅记录，不强制中断）",
                "contextRefs", "上下文引用对象；只传引用，不传完整聊天历史"));
        manifest.put("limits", Map.of(
                "maxContextRefs", MAX_CONTEXT_REFS,
                "maxRefIdLength", MAX_REF_ID_LENGTH,
                "maxInputBytes", maxInputBytes,
                "maxCancelReasonLength", MAX_CANCEL_REASON_LENGTH));
        // 明确声明哪些字段真正生效，避免调用方把“已记录”误当成“已约束”。
        manifest.put("enforcement", Map.of(
                "deadlineAt", Map.of("enforced", deadlineEnabled,
                        "mode", deadlineEnabled ? "checked-before-execution" : "disabled"),
                "budget", Map.of("enforced", false, "mode", "recorded-only"),
                "runtimeTimeout", Map.of("enforced", false)));
        manifest.put("asyncDurability", props(
                "mode", "db-recovery",
                "pendingRequeuedOnRestart", true,
                "runningReplayed", false,
                "multiInstanceLease", false,
                "note", "PENDING 启动重投；RUNNING 超时置 FAILED，写操作不自动重放"));
        manifest.put("observability", Map.of(
                "metricsEndpoint", "/agent/v1/metrics/summary",
                "actuatorMetrics", "/actuator/metrics"));
        manifest.put("downstream", Map.of(
                "enabled", downstreamEnabled,
                "protocol", AgentContract.PROTOCOL));
        manifest.put("capabilities", List.of(
                capability("ticket.query", "查询当前 Actor 可见工单", "ticket:read",
                        false, "READ_ONLY",
                        objectSchema(props(
                                "status", prop("string", "工单状态"),
                                "keyword", prop("string", "关键字"),
                                "priorityCode", prop("string", "优先级编码"),
                                "assigneeId", prop("integer", "处理人 ID"),
                                "startTime", prop("string", "创建时间起，ISO 8601"),
                                "endTime", prop("string", "创建时间止，ISO 8601"),
                                "openOnly", prop("boolean", "仅未结束工单"),
                                "limit", intProp(1, 20, "返回条数，默认 5")), List.of()),
                        resultSchema("array", "工单摘要列表")),
                capability("ticket.detail", "按工单 ID 或工单号查询详情", "ticket:read",
                        false, "READ_ONLY", ticketRefSchema(),
                        resultSchema("object", "工单详情")),
                capability("ticket.similar", "按描述检索相似历史工单", "ticket:read",
                        false, "READ_ONLY",
                        objectSchema(props(
                                "query", prop("string", "查询描述"),
                                "topK", intProp(1, 20, "返回条数，默认 5")), List.of("query")),
                        resultSchema("array", "相似工单列表")),
                capability("knowledge.search", "检索知识库片段", "knowledge:read",
                        false, "READ_ONLY",
                        objectSchema(props(
                                "query", prop("string", "检索问题"),
                                "topK", intProp(1, 20, "返回条数，默认 5"),
                                "categoryCode", prop("string", "知识分类编码")), List.of("query")),
                        resultSchema("array", "知识片段列表")),
                capability("ticket.create", "创建工单草稿，确认后落库", "ticket:create",
                        true, "WRITE",
                        objectSchema(props(
                                "title", prop("string", "标题"),
                                "description", prop("string", "描述"),
                                "ticketTypeCode", prop("string", "工单类型编码"),
                                "priorityCode", prop("string", "优先级编码"),
                                "locationText", prop("string", "位置文本"),
                                "customerId", prop("integer", "客户 ID"),
                                "roomId", prop("integer", "房间 ID"),
                                "equipmentId", prop("integer", "设备 ID"),
                                "projectId", prop("integer", "项目 ID"),
                                "buildingId", prop("integer", "楼栋 ID"),
                                "serviceItemId", prop("integer", "服务项 ID"),
                                "workflowId", prop("integer", "流程 ID"),
                                "appointmentAt", prop("string", "预约时间 ISO 8601"),
                                "contactName", prop("string", "联系人"),
                                "addressText", prop("string", "地址"),
                                "indoorPublicArea", prop("string", "室内/公共区域"),
                                "locationType", prop("string", "位置类型"),
                                "serviceItemText", prop("string", "服务项文本")),
                                List.of("ticketTypeCode")),
                        resultSchema("object", "已创建工单")),
                capability("ticket.export", "导出工单 Excel，确认后生成", "ticket:read",
                        true, "WRITE",
                        objectSchema(props(
                                "status", prop("string", "工单状态"),
                                "keyword", prop("string", "关键字"),
                                "priorityCode", prop("string", "优先级编码"),
                                "ticketTypeCode", prop("string", "工单类型编码"),
                                "location", prop("string", "位置"),
                                "startTime", prop("string", "创建时间起，ISO 8601"),
                                "endTime", prop("string", "创建时间止，ISO 8601")), List.of()),
                        resultSchema("object", "导出文件地址、文件名与行数")),
                capability("ticket.assign", "派单", "ticket:assign", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "assigneeId", prop("integer", "处理人 ID"),
                                "reason", prop("string", "原因")),
                                List.of("ticketId", "assigneeId")),
                        resultSchema("object", "工单详情")),
                capability("ticket.accept", "接单", "ticket:handle", true, "WRITE",
                        ticketActionSchema(),
                        resultSchema("object", "工单详情")),
                capability("ticket.resolve", "提交解决", "ticket:handle", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "serviceItemText", prop("string", "服务项文本"),
                                "reason", prop("string", "原因"),
                                "onsiteSituation", prop("string", "现场情况")),
                                List.of("ticketId", "serviceItemText")),
                        resultSchema("object", "工单详情")),
                capability("ticket.verify", "验证", "ticket:verify", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "approved", prop("boolean", "是否通过，默认 true"),
                                "reason", prop("string", "原因")), List.of("ticketId")),
                        resultSchema("object", "工单详情")),
                capability("ticket.close", "关闭", "ticket:close", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "reason", prop("string", "原因"),
                                "closeReasonCode", prop("string", "关闭原因编码")),
                                List.of("ticketId")),
                        resultSchema("object", "工单详情")),
                capability("ticket.cancel", "取消", "ticket:close", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "reason", prop("string", "原因"),
                                "cancelReason", prop("string", "取消原因编码")),
                                List.of("ticketId")),
                        resultSchema("object", "工单详情")),
                capability("ticket.hold", "挂起", "ticket:handle", true, "WRITE",
                        ticketActionSchema(),
                        resultSchema("object", "工单详情")),
                capability("ticket.resume", "恢复", "ticket:handle", true, "WRITE",
                        ticketActionSchema(),
                        resultSchema("object", "工单详情")),
                capability("ticket.finish", "结束", "ticket:close", true, "WRITE",
                        ticketActionSchema(),
                        resultSchema("object", "工单详情")),
                capability("ticket.comment.add", "添加评论", "ticket:read", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "content", prop("string", "评论内容"),
                                "visibility", prop("string", "PUBLIC/INTERNAL，默认 PUBLIC")),
                                List.of("ticketId", "content")),
                        resultSchema("object", "评论")),
                capability("ticket.material.add", "添加使用材料", "ticket:handle", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "materialName", prop("string", "材料名称"),
                                "specification", prop("string", "规格"),
                                "quantity", prop("number", "数量"),
                                "unit", prop("string", "单位"),
                                "amount", prop("number", "金额"),
                                "remark", prop("string", "备注")),
                                List.of("ticketId", "materialName")),
                        resultSchema("object", "材料记录")),
                capability("ticket.visit.add", "登记回访", "ticket:close", true, "WRITE",
                        objectSchema(props(
                                "ticketId", prop("integer", "工单 ID"),
                                "required", prop("boolean", "是否需要回访"),
                                "visitMethod", prop("string", "回访方式"),
                                "visitedAt", prop("string", "回访时间"),
                                "visitResult", prop("string", "回访结果"),
                                "status", prop("string", "状态，默认 DONE"),
                                "remark", prop("string", "备注")), List.of("ticketId")),
                        resultSchema("object", "回访记录"))
        ));
        return manifest;
    }

    public Map<String, Object> agentCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", "Property Maintenance Agent");
        card.put("description", "物业报修、工单流转、现场处理、知识检索与导出子 Agent");
        card.put("url", cardBaseUrl);
        card.put("version", AgentContract.VERSION);
        card.put("protocolVersion", AgentContract.PROTOCOL);
        card.put("capabilities", Map.of(
                "streaming", true,
                "pushNotifications", false,
                "stateTransitionHistory", true
        ));
        card.put("defaultInputModes", List.of("application/json"));
        card.put("defaultOutputModes", List.of("application/json", "text/event-stream"));
        card.put("skills", manifest().get("capabilities"));
        card.put("endpoints", manifest().get("endpoints"));
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
        String parentTaskId = optionalRefId(request.parentTaskId(), "parentTaskId");
        String correlationId = optionalRefId(request.correlationId(), "correlationId");
        if (request.contextRefs() != null && request.contextRefs().size() > MAX_CONTEXT_REFS) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "contextRefs 最多 " + MAX_CONTEXT_REFS + " 项");
        }
        LocalDateTime deadlineAt = parseDeadline(request.deadlineAt());
        if (deadlineEnabled && deadlineAt != null
                && !deadlineAt.isAfter(LocalDateTime.now(ZoneOffset.UTC))) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "deadlineAt 已过期");
        }
        String inputJson = toJson(request.input() == null ? Map.of() : request.input());
        if (inputJson.getBytes(StandardCharsets.UTF_8).length > maxInputBytes) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "input 超过 " + maxInputBytes + " 字节");
        }
        String taskId = UUID.randomUUID().toString();
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setTaskId(taskId);
        entity.setAgentId(AgentContract.AGENT_ID);
        entity.setCapability(capability);
        entity.setActorId(actorId);
        entity.setActorRoles(String.join(",", roles == null ? List.of() : roles));
        entity.setClientTaskId(clientTaskId);
        entity.setDelegationId(delegationId);
        entity.setParentTaskId(parentTaskId);
        entity.setCorrelationId(correlationId);
        entity.setDeadlineAt(deadlineAt);
        entity.setInputJson(inputJson);
        entity.setContextRefsJson(request.contextRefs() == null ? null
                : toJson(request.contextRefs()));
        entity.setBudgetJson(request.budget() == null ? null : toJson(request.budget()));
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
        recordMetrics(entity);

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

    /**
     * 供主 Agent 崩溃恢复：按幂等键查询自己的任务，不暴露其他 Actor 的任务是否存在。
     */
    public AgentTaskView findByClientTaskId(String clientTaskId, long actorId) {
        if (!StringUtils.hasText(clientTaskId)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "clientTaskId 不能为空");
        }
        AgentTaskEntity entity = findExisting(actorId, clientTaskId.trim());
        if (entity == null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND, "任务不存在");
        }
        return toView(entity);
    }

    public AgentTaskView cancel(String taskId, long actorId) {
        return cancel(taskId, null, actorId);
    }

    public AgentTaskView cancel(String taskId, String reason, long actorId) {
        AgentTaskEntity entity = find(taskId);
        requireOwner(entity, actorId, "无权取消该任务");
        if (!List.of("PENDING", "RUNNING", "WAITING_CONFIRM").contains(entity.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "当前任务不可取消");
        }
        String cancelReason = normalizeCancelReason(reason);
        int updated = taskMapper.update(null, Wrappers.<AgentTaskEntity>lambdaUpdate()
                .eq(AgentTaskEntity::getTenantId, TENANT)
                .eq(AgentTaskEntity::getTaskId, taskId)
                .in(AgentTaskEntity::getStatus, List.of("PENDING", "RUNNING", "WAITING_CONFIRM"))
                .set(AgentTaskEntity::getStatus, "CANCELLED")
                .set(AgentTaskEntity::getErrorCode, null)
                .set(AgentTaskEntity::getErrorMessage, "调用方已取消")
                .set(AgentTaskEntity::getCancelReason, cancelReason)
                .set(AgentTaskEntity::getConfirmationToken, null)
                .set(AgentTaskEntity::getConfirmationExpiresAt, null)
                .set(AgentTaskEntity::getUpdatedAt, LocalDateTime.now(ZoneOffset.UTC)));
        if (updated == 0) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "当前任务不可取消");
        }
        eventService.record(taskId, "CANCELLED", "调用方已取消");
        metrics.taskStatus(entity.getCapability(), "CANCELLED");
        return toView(find(taskId));
    }

    public List<AgentTaskEventView> events(String taskId, long actorId) {
        return events(taskId, actorId, null);
    }

    public List<AgentTaskEventView> events(String taskId, long actorId, Long afterSeq) {
        return eventsAfter(taskId, actorId, afterSeq).stream()
                .map(this::toEventView)
                .toList();
    }

    public List<AgentTaskEvent> eventsAfter(String taskId, long actorId, Long afterSeq) {
        AgentTaskEntity task = find(taskId);
        requireOwner(task, actorId, "无权访问该任务");
        return eventService.listAfter(taskId, afterSeq);
    }

    public AgentTaskView confirm(String taskId, String confirmationToken,
                                 long actorId, List<String> roles) {
        return confirm(taskId, confirmationToken, "APPROVE", actorId, roles);
    }

    public AgentTaskView confirm(String taskId, String confirmationToken, String decision,
                                 long actorId, List<String> roles) {
        AgentTaskEntity entity = find(taskId);
        requireOwner(entity, actorId, "无权确认该任务");
        if (!"WAITING_CONFIRM".equals(entity.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "该任务不需要确认");
        }
        String normalizedDecision = normalizeDecision(decision);
        if (!StringUtils.hasText(entity.getConfirmationToken())
                || !entity.getConfirmationToken().equals(confirmationToken)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "确认令牌无效");
        }
        if (entity.getConfirmationExpiresAt() == null
                || entity.getConfirmationExpiresAt().isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            expire(entity);
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "确认已过期");
        }

        entity.setConfirmedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setConfirmationToken(null);
        entity.setConfirmationExpiresAt(null);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));

        if ("REJECT".equals(normalizedDecision)) {
            entity.setStatus("REJECTED");
            entity.setErrorCode(null);
            entity.setErrorMessage("调用方拒绝执行");
            entity.setResultJson(toJson(Map.of(
                    "summary", "调用方已拒绝执行",
                    "capability", entity.getCapability())));
            taskMapper.updateById(entity);
            eventService.record(taskId, "REJECTED", "调用方拒绝执行");
            metrics.taskStatus(entity.getCapability(), "REJECTED");
            return toView(entity);
        }

        if (MODE_ASYNC.equals(entity.getExecutionMode())) {
            entity.setStatus("PENDING");
            entity.setResultJson(toJson(Map.of(
                    "summary", "已确认，任务进入队列",
                    "capability", entity.getCapability())));
            taskMapper.updateById(entity);
            eventService.record(taskId, "PENDING", "已确认，任务进入队列");
            recordMetrics(entity);
            enqueue(taskId, entity);
            return toView(entity);
        }

        taskWorker.executeInline(entity, roles);
        taskMapper.updateById(entity);
        eventService.record(taskId, entity.getStatus(), entity.getErrorMessage());
        recordMetrics(entity);
        return toView(entity);
    }

    private void expire(AgentTaskEntity entity) {
        entity.setStatus("FAILED");
        entity.setErrorCode(String.valueOf(ErrorCode.AUTH_TOKEN_EXPIRED.getCode()));
        entity.setErrorMessage("确认已过期");
        entity.setConfirmationToken(null);
        entity.setConfirmationExpiresAt(null);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(entity);
        eventService.record(entity.getTaskId(), "TASK_CONFIRM_EXPIRED", "FAILED", "确认已过期");
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
            metrics.taskStatus(entity.getCapability(), "FAILED");
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

    private String normalizeDecision(String decision) {
        if (!StringUtils.hasText(decision)) {
            return "APPROVE";
        }
        String normalized = decision.trim().toUpperCase();
        if (!List.of("APPROVE", "REJECT").contains(normalized)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "decision 只支持 APPROVE/REJECT");
        }
        return normalized;
    }

    private String normalizeCancelReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return null;
        }
        String normalized = reason.trim();
        if (normalized.length() > MAX_CANCEL_REASON_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    "reason 长度不能超过 " + MAX_CANCEL_REASON_LENGTH);
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
        String status = entity.getStatus();
        return new AgentTaskView(
                entity.getTaskId(),
                entity.getAgentId(),
                entity.getCapability(),
                capabilityId(entity.getCapability()),
                status,
                result,
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getTraceId(),
                entity.getLatencyMs() == null ? 0 : entity.getLatencyMs(),
                entity.getInputTokens(),
                entity.getOutputTokens(),
                entity.getCostMicros(),
                usage(entity),
                entity.getExecutionMode() == null ? MODE_SYNC : entity.getExecutionMode(),
                entity.getDelegationId(),
                entity.getConfirmationToken(),
                entity.getConfirmationExpiresAt() == null ? null
                        : entity.getConfirmationExpiresAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                entity.getCreatedAt() == null ? null
                        : entity.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                entity.getParentTaskId(),
                entity.getCorrelationId(),
                entity.getDeadlineAt() == null ? null
                        : entity.getDeadlineAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                readMap(entity.getContextRefsJson()),
                readMap(entity.getBudgetJson()),
                entity.getClientTaskId(),
                progress(status),
                currentStep(status),
                entity.getCancelReason()
        );
    }

    private Map<String, Object> usage(AgentTaskEntity entity) {
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("inputTokens", entity.getInputTokens());
        usage.put("outputTokens", entity.getOutputTokens());
        usage.put("costMicros", entity.getCostMicros());
        boolean measured = entity.getInputTokens() != null
                || entity.getOutputTokens() != null
                || entity.getCostMicros() != null;
        usage.put("source", measured ? "provider" : "not-measured");
        return usage;
    }

    private int progress(String status) {
        if (status == null) {
            return 0;
        }
        return switch (status) {
            case "RUNNING" -> 50;
            case "SUCCEEDED", "FAILED", "CANCELLED", "REJECTED" -> 100;
            default -> 0;
        };
    }

    private String currentStep(String status) {
        if (status == null) {
            return "UNKNOWN";
        }
        return switch (status) {
            case "PENDING" -> "QUEUED";
            case "WAITING_CONFIRM" -> "WAITING_CONFIRM";
            case "RUNNING" -> "EXECUTING";
            case "SUCCEEDED" -> "DONE";
            case "FAILED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            case "REJECTED" -> "REJECTED";
            default -> "UNKNOWN";
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String optionalRefId(String value, String field) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_REF_ID_LENGTH) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR,
                    field + " 长度不能超过 " + MAX_REF_ID_LENGTH);
        }
        return trimmed;
    }

    private LocalDateTime parseDeadline(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String text = value.trim();
        try {
            return OffsetDateTime.parse(text)
                    .atZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (Exception e) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "deadlineAt 必须是 ISO 8601 时间");
            }
        }
    }

    private void recordMetrics(AgentTaskEntity entity) {
        metrics.taskStatus(entity.getCapability(), entity.getStatus());
        if (AgentTaskEventService.isTerminal(entity.getStatus())) {
            metrics.taskLatency(entity.getCapability(),
                    entity.getLatencyMs() == null ? 0 : entity.getLatencyMs());
        }
    }

    private AgentTaskEventView toEventView(AgentTaskEvent event) {
        return new AgentTaskEventView(event.id(), event.seq(), event.taskId(), event.type(),
                event.status(), event.message(), event.createdAt());
    }

    private boolean isWriteCapability(String capability) {
        return List.of("ticket.create", "ticket.export", "ticket.assign", "ticket.accept",
                        "ticket.resolve", "ticket.verify", "ticket.close", "ticket.cancel",
                        "ticket.hold", "ticket.resume", "ticket.finish", "ticket.comment.add",
                        "ticket.material.add", "ticket.visit.add")
                .contains(capability);
    }

    private String capabilityId(String capability) {
        return capability == null ? null : CAPABILITY_PREFIX + capability;
    }

    private Map<String, Object> capability(String name, String description, String permission,
                                           boolean requiresConfirm, String mode,
                                           Map<String, Object> inputSchema,
                                           Map<String, Object> outputSchema) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("capabilityId", capabilityId(name));
        item.put("name", name);
        item.put("description", description);
        item.put("readOnly", "READ_ONLY".equals(mode));
        item.put("requiresConfirmation", requiresConfirm);
        item.put("requiresConfirm", requiresConfirm);
        item.put("executionModes", List.of(MODE_SYNC, MODE_ASYNC));
        item.put("scopes", List.of(name));
        item.put("permission", permission);
        item.put("mode", mode);
        item.put("inputSchema", inputSchema);
        item.put("outputSchema", outputSchema);
        return item;
    }

    private Map<String, Object> ticketActionSchema() {
        return objectSchema(props(
                "ticketId", prop("integer", "工单 ID"),
                "reason", prop("string", "原因")), List.of("ticketId"));
    }

    private Map<String, Object> ticketRefSchema() {
        Map<String, Object> schema = objectSchema(props(
                "ticketId", prop("integer", "工单 ID"),
                "ticketNo", prop("string", "工单号")), List.of());
        schema.put("anyOf", List.of(
                Map.of("required", List.of("ticketId")),
                Map.of("required", List.of("ticketNo"))));
        return schema;
    }

    private Map<String, Object> objectSchema(Map<String, Object> properties,
                                             List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", false);
        return schema;
    }

    private Map<String, Object> resultSchema(String type, String description) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        if ("array".equals(type)) {
            result.put("items", Map.of("type", "object"));
        }
        result.put("description", description);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("result", result));
        schema.put("required", List.of("result"));
        return schema;
    }

    private Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private Map<String, Object> intProp(int min, int max, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", "integer");
        property.put("minimum", min);
        property.put("maximum", max);
        property.put("description", description);
        return property;
    }

    private Map<String, Object> props(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return map;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
