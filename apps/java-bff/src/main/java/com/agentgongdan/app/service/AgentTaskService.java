package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.AgentTaskEntity;
import com.agentgongdan.adapters.persistence.mapper.AgentTaskMapper;
import com.agentgongdan.adapters.web.dto.AgentTaskRequest;
import com.agentgongdan.adapters.web.dto.AgentTaskView;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
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
    private final AgentTaskMapper taskMapper;
    private final ObjectMapper objectMapper;

    public AgentTaskService(TicketService ticketService,
                            KnowledgeService knowledgeService,
                            TicketVectorService ticketVectorService,
                            TicketExportJobService ticketExportJobService,
                            AgentTaskMapper taskMapper,
                            ObjectMapper objectMapper) {
        this.ticketService = ticketService;
        this.knowledgeService = knowledgeService;
        this.ticketVectorService = ticketVectorService;
        this.ticketExportJobService = ticketExportJobService;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> manifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("agentId", AGENT_ID);
        manifest.put("name", "物业报修与工单 Agent");
        manifest.put("version", "0.2.0");
        manifest.put("protocol", "agent-task-v1");
        manifest.put("taskEndpoint", "/agent/v1/tasks");
        manifest.put("auth", Map.of("type", "internal-token", "header", "X-Internal-Token"));
        manifest.put("capabilities", List.of(
                capability("ticket.query", "查询当前 Actor 可见工单", "ticket:read", false, "READ_ONLY"),
                capability("ticket.detail", "按工单 ID 或工单号查询详情", "ticket:read", false, "READ_ONLY"),
                capability("ticket.similar", "按描述检索相似历史工单", "ticket:read", false, "READ_ONLY"),
                capability("knowledge.search", "检索知识库片段", "knowledge:read", false, "READ_ONLY"),
                capability("ticket.create", "创建工单草稿，确认后落库", "ticket:create", true, "WRITE"),
                capability("ticket.export", "导出工单 Excel，确认后生成", "ticket:read", true, "WRITE")
        ));
        return manifest;
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
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setUpdatedAt(entity.getCreatedAt());

        boolean requiresConfirm = "ticket.create".equals(request.taskType())
                || "ticket.export".equals(request.taskType());
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
        return toView(entity);
    }

    public AgentTaskView get(String taskId, long actorId) {
        AgentTaskEntity entity = find(taskId);
        if (!entity.getActorId().equals(actorId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "无权访问该任务");
        }
        return toView(entity);
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
                entity.getConfirmationToken(),
                entity.getConfirmationExpiresAt() == null ? null
                        : entity.getConfirmationExpiresAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME),
                entity.getCreatedAt() == null ? null
                        : entity.getCreatedAt().atOffset(ZoneOffset.UTC)
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        );
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
