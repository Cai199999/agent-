package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.entity.AgentTaskEventEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskEventMapper;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.adapters.web.dto.AddTicketMaterialRequest;
import com.agentgongdan.adapters.web.dto.AddTicketVisitRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskEventView;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.adapters.web.dto.TransitionRequest;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 子 Agent Task API。
 * P0：只读能力 + 内存任务；P1：agent_task 持久化 + 写能力确认。
 */
@Service
public class AgentTaskService {

    private static final String AGENT_ID = "property-maintenance";
    private static final long TENANT = 0L;
    private static final long CONFIRM_TTL_MINUTES = 10;

    private final TicketService ticketService;
    private final KnowledgeService knowledgeService;
    private final TicketVectorService ticketVectorService;
    private final TicketExportJobService ticketExportJobService;
    private final TicketAccessService ticketAccessService;
    private final TicketMaterialService ticketMaterialService;
    private final TicketVisitService ticketVisitService;
    private final AgentTaskMapper taskMapper;
    private final AgentTaskEventMapper eventMapper;
    private final ObjectMapper objectMapper;

    public AgentTaskService(TicketService ticketService,
                            KnowledgeService knowledgeService,
                            TicketVectorService ticketVectorService,
                            TicketExportJobService ticketExportJobService,
                            TicketAccessService ticketAccessService,
                            TicketMaterialService ticketMaterialService,
                            TicketVisitService ticketVisitService,
                            AgentTaskMapper taskMapper,
                            AgentTaskEventMapper eventMapper,
                            ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.knowledgeService = knowledgeService;
        this.ticketVectorService = ticketVectorService;
        this.ticketExportJobService = ticketExportJobService;
        this.ticketAccessService = ticketAccessService;
        this.ticketMaterialService = ticketMaterialService;
        this.ticketVisitService = ticketVisitService;
        this.taskMapper = taskMapper;
        this.eventMapper = eventMapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", AGENT_ID);
        manifest.put("name", "物业报修与工单 Agent");
        manifest.put("version", "0.4.0");
        manifest.put("protocol", "agent-task-v1");
        manifest.put("taskEndpoint", "/agent/v1/tasks");
        manifest.put("auth", Map.of("type", "internal-token", "header", "X-Internal-Token"));
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
        card.put("version", "0.4.0");
        card.put("protocolVersion", "agent-task-v1");
        card.put("capabilities", Map.of(
                "streaming", false,
                "pushNotifications", false,
                "stateTransitionHistory", true
        ));
        card.put("defaultInputModes", List.of("application/json"));
        card.put("defaultOutputModes", List.of("application/json"));
        card.put("skills", manifest().get("capabilities"));
        card.put("securitySchemes", Map.of(
                "internalToken", Map.of("type", "apiKey", "in", "header", "name", "X-Internal-Token")
        ));
        return card;
    }

    public AgentTaskView submit(AgentTaskRequest request, long actorId,
                                List<String> roles, String traceId) {
        if (request == null || !StringUtils.hasText(request.taskType())) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "taskType 不能为空");
        }
        String clientTaskId = StringUtils.hasText(request.clientTaskId())
                ? request.clientTaskId().trim() : null;
        if (clientTaskId != null) {
            AgentTaskEntity existing = taskMapper.selectOne(Wrappers.<AgentTaskEntity>lambdaQuery()
                    .eq(AgentTaskEntity::getTenantId, TENANT)
                    .eq(AgentTaskEntity::getActorId, actorId)
                    .eq(AgentTaskEntity::getClientTaskId, clientTaskId)
                    .last("LIMIT 1"));
            if (existing != null) {
                return toView(existing);
            }
        }

        String taskId = UUID.randomUUID().toString();
        AgentTaskEntity entity = new AgentTaskEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setTaskId(taskId);
        entity.setAgentId(AGENT_ID);
        entity.setCapability(request.taskType());
        entity.setActorId(actorId);
        entity.setActorRoles(String.join(",", roles == null ? List.of() : roles));
        entity.setClientTaskId(clientTaskId);
        entity.setInputJson(toJson(request.input() == null ? Map.of() : request.input()));
        entity.setTraceId(traceId);
        entity.setExecutionMode("SYNC");
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getCreatedAt());

        boolean requiresConfirm = isWriteCapability(request.taskType());
        if (requiresConfirm) {
            entity.setStatus("WAITING_CONFIRM");
            entity.setConfirmationToken(UUID.randomUUID().toString().replace("-", ""));
            entity.setConfirmationExpiresAt(LocalDateTime.now(ZoneOffset.UTC)
                    .plusMinutes(CONFIRM_TTL_MINUTES));
            entity.setResultJson(toJson(Map.of(
                    "summary", "该能力需要人工确认后才会执行",
                    "capability", request.taskType())));
        } else {
            applyExecution(entity, roles);
        }
        try {
            taskMapper.insert(entity);
        } catch (DuplicateKeyException e) {
            AgentTaskEntity existing = taskMapper.selectOne(Wrappers.<AgentTaskEntity>lambdaQuery()
                    .eq(AgentTaskEntity::getTenantId, TENANT)
                    .eq(AgentTaskEntity::getActorId, actorId)
                    .eq(AgentTaskEntity::getClientTaskId, clientTaskId)
                    .last("LIMIT 1"));
            if (existing != null) {
                return toView(existing);
            }
            throw e;
        }
        recordEvent(taskId, entity.getStatus(), entity.getErrorMessage());
        return toView(entity);
    }

    public AgentTaskView get(String taskId, long actorId) {
        AgentTaskEntity entity = find(taskId);
        if (!entity.getActorId().equals(actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权访问该任务");
        }
        return toView(entity);
    }

    public AgentTaskView cancel(String taskId, long actorId) {
        AgentTaskEntity entity = find(taskId);
        if (!entity.getActorId().equals(actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权取消该任务");
        }
        if (!List.of("PENDING", "RUNNING", "WAITING_CONFIRM").contains(entity.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "当前任务不可取消");
        }
        entity.setStatus("CANCELLED");
        entity.setErrorCode(null);
        entity.setErrorMessage("调用方已取消");
        entity.setConfirmationToken(null);
        entity.setConfirmationExpiresAt(null);
        entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        taskMapper.updateById(entity);
        recordEvent(taskId, entity.getStatus(), entity.getErrorMessage());
        return toView(entity);
    }

    public List<AgentTaskEventView> events(String taskId, long actorId) {
        AgentTaskEntity task = find(taskId);
        if (!task.getActorId().equals(actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权访问该任务");
        }
        return eventMapper.selectList(Wrappers.<AgentTaskEventEntity>lambdaQuery()
                        .eq(AgentTaskEventEntity::getTenantId, TENANT)
                        .eq(AgentTaskEventEntity::getTaskId, taskId)
                        .orderByAsc(AgentTaskEventEntity::getId))
                .stream()
                .map(item -> new AgentTaskEventView(
                        String.valueOf(item.getId()),
                        item.getTaskId(),
                        item.getStatus(),
                        item.getMessage(),
                        item.getCreatedAt() == null ? null
                                : item.getCreatedAt().atOffset(ZoneOffset.UTC)
                                .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)))
                .toList();
    }

    public AgentTaskView confirm(String taskId, String confirmationToken,
                                 long actorId, List<String> roles) {
        AgentTaskEntity entity = find(taskId);
        if (!entity.getActorId().equals(actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权确认该任务");
        }
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
            taskMapper.updateById(entity);
            throw new ApiException(ErrorCode.AUTH_TOKEN_EXPIRED, "确认已过期");
        }
        applyExecution(entity, roles);
        entity.setConfirmedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setConfirmationToken(null);
        entity.setConfirmationExpiresAt(null);
        taskMapper.updateById(entity);
        recordEvent(taskId, entity.getStatus(), entity.getErrorMessage());
        return toView(entity);
    }

    private void applyExecution(AgentTaskEntity entity, List<String> roles) {
        long started = System.currentTimeMillis();
        try {
            Object result = dispatch(entity.getCapability(), fromJson(entity.getInputJson()),
                    entity.getActorId(), roles, entity.getTaskId());
            entity.setStatus("SUCCEEDED");
            entity.setResultJson(toJson(result));
            entity.setErrorCode(null);
            entity.setErrorMessage(null);
            entity.setUpdatedAt(LocalDateTime.now(ZoneOffset.UTC));
        } catch (ApiException ex) {
            entity.setStatus("FAILED");
            entity.setErrorCode(String.valueOf(ex.getErrorCode().getCode()));
            entity.setErrorMessage(ex.getMessage());
        } catch (Exception ex) {
            entity.setStatus("FAILED");
            entity.setErrorCode(String.valueOf(ErrorCode.SYS_INTERNAL_ERROR.getCode()));
            entity.setErrorMessage(ErrorCode.SYS_INTERNAL_ERROR.getMessage());
        }
        entity.setLatencyMs((int) (System.currentTimeMillis() - started));
    }

    private Object dispatch(String capability, Map<String, Object> input,
                            long actorId, List<String> roles, String taskId) {
        Map<String, Object> args = input == null ? Map.of() : input;
        return switch (capability) {
            case "ticket.query" -> ticketService.listInternal(
                    actorId, roles, str(args.get("status")), str(args.get("keyword")),
                    intValue(args.get("limit"), 5, 1, 20));
            case "ticket.detail" -> {
                String ref = firstText(str(args.get("ticketId")), str(args.get("ticketNo")));
                yield ticketService.detailInternalRef(ref, actorId, roles);
            }
            case "ticket.similar" -> ticketVectorService.similarByQuery(
                    required(args, "query"), intValue(args.get("topK"), 5, 1, 20));
            case "knowledge.search" -> knowledgeService.search(new KnowledgeSearchRequest(
                    required(args, "query"), intValue(args.get("topK"), 5, 1, 20),
                    str(args.get("categoryCode"))));
            case "ticket.create" -> {
                requireCreateRole(roles);
                yield ticketService.create(buildCreateRequest(args), actorId, "agent-task:" + taskId);
            }
            case "ticket.export" -> {
                if (roles == null || roles.isEmpty()) {
                    throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "没有导出权限");
                }
                boolean privileged = roles.contains("ADMIN") || roles.contains("DISPATCHER")
                        || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
                yield ticketExportJobService.export(
                        str(args.get("status")), str(args.get("keyword")),
                        str(args.get("priorityCode")), str(args.get("ticketTypeCode")),
                        str(args.get("location")), null,
                        privileged ? null : actorId,
                        parseTime(args.get("startTime")), parseTime(args.get("endTime")));
            }
            case "ticket.assign" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                Long assigneeId = longValue(args.get("assigneeId"));
                if (assigneeId == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少参数: assigneeId");
                }
                yield ticketService.transition(ticketId, "assign",
                        transition(ticket.getVersion(), assigneeId, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.accept" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandlerOrUnassigned(ticketId, actorId, roles);
                yield ticketService.transition(ticketId, "accept",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.resolve" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandler(ticketId, actorId, roles);
                String serviceItemText = required(args, "serviceItemText");
                yield ticketService.transition(ticketId, "resolve",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, str(args.get("onsiteSituation")), serviceItemText), actorId);
            }
            case "ticket.verify" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReporterOrPrivileged(ticketId, actorId, roles);
                boolean approved = Boolean.parseBoolean(String.valueOf(args.getOrDefault("approved", true)));
                yield ticketService.transition(ticketId, "verify",
                        transition(ticket.getVersion(), null, approved, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.close" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketService.transition(ticketId, "close",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                str(args.get("closeReasonCode")), null, null, null), actorId);
            }
            case "ticket.cancel" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketService.transition(ticketId, "cancel",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, str(args.get("cancelReason")), null, null), actorId);
            }
            case "ticket.hold" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandler(ticketId, actorId, roles);
                yield ticketService.transition(ticketId, "hold",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.resume" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireHandler(ticketId, actorId, roles);
                yield ticketService.transition(ticketId, "resume",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.finish" -> {
                long ticketId = requiredTicketId(args);
                var ticket = ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketService.transition(ticketId, "finish",
                        transition(ticket.getVersion(), null, null, str(args.get("reason")),
                                null, null, null, null), actorId);
            }
            case "ticket.comment.add" -> {
                long ticketId = requiredTicketId(args);
                ticketAccessService.requireReadable(ticketId, actorId, roles);
                yield ticketService.addComment(ticketId, required(args, "content"),
                        str(args.getOrDefault("visibility", "PUBLIC")), actorId);
            }
            case "ticket.material.add" -> {
                long ticketId = requiredTicketId(args);
                ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireHandleRole(roles);
                yield ticketMaterialService.add(ticketId, new AddTicketMaterialRequest(
                        required(args, "materialName"),
                        str(args.get("specification")),
                        decimal(args.get("quantity")),
                        str(args.get("unit")),
                        decimal(args.get("amount")),
                        str(args.get("remark"))), actorId);
            }
            case "ticket.visit.add" -> {
                long ticketId = requiredTicketId(args);
                ticketAccessService.requireReadable(ticketId, actorId, roles);
                requireManager(roles);
                yield ticketVisitService.add(ticketId, new AddTicketVisitRequest(
                        boolValue(args.get("required")),
                        str(args.get("visitMethod")),
                        str(args.get("visitedAt")),
                        str(args.get("visitResult")),
                        str(args.getOrDefault("status", "DONE")),
                        str(args.get("remark"))), actorId);
            }
            default -> throw new ApiException(ErrorCode.VALIDATION_ERROR, "不支持的能力: " + capability);
        };
    }

    private CreateTicketRequest buildCreateRequest(Map<String, Object> args) {
        return new CreateTicketRequest(
                str(args.get("title")),
                str(args.get("description")),
                required(args, "ticketTypeCode"),
                str(args.get("priorityCode")),
                str(args.get("locationText")),
                longValue(args.get("customerId")),
                longValue(args.get("roomId")),
                longValue(args.get("equipmentId")),
                longValue(args.get("projectId")),
                longValue(args.get("buildingId")),
                longValue(args.get("serviceItemId")),
                longValue(args.get("workflowId")),
                str(args.get("appointmentAt")),
                str(args.get("contactName")),
                str(args.get("addressText")),
                str(args.get("indoorPublicArea")),
                str(args.get("locationType")),
                str(args.get("serviceItemText"))
        );
    }

    private void requireCreateRole(List<String> roles) {
        if (roles == null || roles.stream().noneMatch(role ->
                List.of("ADMIN", "DISPATCHER", "ENGINEER", "REPORTER").contains(role))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "没有建单权限");
        }
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
                entity.getExecutionMode() == null ? "SYNC" : entity.getExecutionMode(),
                entity.getConfirmationToken(),
                entity.getConfirmationExpiresAt() == null ? null
                        : entity.getConfirmationExpiresAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                entity.getCreatedAt() == null ? null
                        : entity.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
    }

    private boolean isWriteCapability(String capability) {
        return List.of("ticket.create", "ticket.export", "ticket.assign", "ticket.accept",
                        "ticket.resolve", "ticket.verify", "ticket.close", "ticket.cancel",
                        "ticket.hold", "ticket.resume", "ticket.finish", "ticket.comment.add",
                        "ticket.material.add", "ticket.visit.add")
                .contains(capability);
    }

    private long requiredTicketId(Map<String, Object> args) {
        Long ticketId = longValue(args.get("ticketId"));
        if (ticketId == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少参数: ticketId");
        }
        return ticketId;
    }

    private TransitionRequest transition(Integer version, Long assigneeId, Boolean approved,
                                         String reason, String closeReasonCode, String cancelReason,
                                         String onsiteSituation, String serviceItemText) {
        return new TransitionRequest(version, assigneeId, approved, reason,
                closeReasonCode, cancelReason, onsiteSituation, serviceItemText);
    }

    private void requireManager(List<String> roles) {
        if (roles == null || roles.stream().noneMatch(role ->
                List.of("ADMIN", "DISPATCHER").contains(role))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "需要管理/调度权限");
        }
    }

    private void requireHandleRole(List<String> roles) {
        if (roles == null || roles.stream().noneMatch(role ->
                List.of("ADMIN", "DISPATCHER", "ENGINEER", "INSPECTOR").contains(role))) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "需要处理权限");
        }
    }

    private java.math.BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new java.math.BigDecimal(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean boolValue(Object value) {
        if (value == null) {
            return null;
        }
        return Boolean.parseBoolean(String.valueOf(value));
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

    private String required(Map<String, Object> args, String key) {
        String value = str(args.get(key));
        if (!StringUtils.hasText(value)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "缺少参数: " + key);
        }
        return value;
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private String str(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    private Long longValue(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private int intValue(Object value, int defaultValue, int min, int max) {
        try {
            int number = value == null ? defaultValue : Integer.parseInt(String.valueOf(value));
            return Math.min(Math.max(number, min), max);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private LocalDateTime parseTime(Object value) {
        String text = str(value);
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(text);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private void recordEvent(String taskId, String status, String message) {
        AgentTaskEventEntity event = new AgentTaskEventEntity();
        event.setId(IdWorker.getId());
        event.setTenantId(TENANT);
        event.setTaskId(taskId);
        event.setStatus(status);
        event.setMessage(message);
        event.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        eventMapper.insert(event);
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
