package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.ChatSessionEntity;
import com.agentgongdan.adapters.persistence.entity.ChatSessionTicketEntity;
import com.agentgongdan.adapters.persistence.entity.ChatToolCallEntity;
import com.agentgongdan.adapters.persistence.entity.TriageRecordEntity;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionTicketMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatToolCallMapper;
import com.agentgongdan.adapters.persistence.mapper.TriageRecordMapper;
import com.agentgongdan.adapters.web.dto.AddTicketMaterialRequest;
import com.agentgongdan.adapters.web.dto.AddTicketVisitRequest;
import com.agentgongdan.adapters.web.dto.ChatToolCallView;
import com.agentgongdan.adapters.web.dto.ConfirmToolCallRequest;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TicketExportDownloadView;
import com.agentgongdan.adapters.web.dto.TransitionRequest;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具调用轨迹与高风险工具确认。
 */
@Service
public class ChatToolCallService {

    private static final Logger log = LoggerFactory.getLogger(ChatToolCallService.class);
    private static final long TENANT = 0L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final Map<String, String> ACTION_BY_TOOL = Map.of(
            "ticket_assign", "assign",
            "ticket_accept", "accept",
            "ticket_resolve", "resolve",
            "ticket_verify", "verify",
            "ticket_close", "close",
            "ticket_cancel", "cancel",
            "ticket_hold", "hold",
            "ticket_resume", "resume",
            "ticket_finish", "finish");

    private static final Set<String> ACTION_TOOLS = Set.of(
            "ticket_assign", "ticket_accept", "ticket_resolve", "ticket_verify",
            "ticket_close", "ticket_cancel", "ticket_hold", "ticket_resume",
            "ticket_finish", "ticket_comment_add", "ticket_material_add",
            "ticket_visit_add");

    private final ChatToolCallMapper toolCallMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatSessionTicketMapper sessionTicketMapper;
    private final TriageRecordMapper triageRecordMapper;
    private final TicketService ticketService;
    private final TicketExportJobService ticketExportJobService;
    private final TicketAccessService ticketAccessService;
    private final TicketMaterialService ticketMaterialService;
    private final TicketVisitService ticketVisitService;
    private final ObjectMapper objectMapper;
    private final AuditPort auditPort;

    public ChatToolCallService(ChatToolCallMapper toolCallMapper,
                               ChatSessionMapper sessionMapper,
                               ChatSessionTicketMapper sessionTicketMapper,
                               TriageRecordMapper triageRecordMapper,
                               TicketService ticketService,
                               TicketExportJobService ticketExportJobService,
                               TicketAccessService ticketAccessService,
                               TicketMaterialService ticketMaterialService,
                               TicketVisitService ticketVisitService,
                               ObjectMapper objectMapper,
                               AuditPort auditPort) {
        this.toolCallMapper = toolCallMapper;
        this.sessionMapper = sessionMapper;
        this.sessionTicketMapper = sessionTicketMapper;
        this.triageRecordMapper = triageRecordMapper;
        this.ticketService = ticketService;
        this.ticketExportJobService = ticketExportJobService;
        this.ticketAccessService = ticketAccessService;
        this.ticketMaterialService = ticketMaterialService;
        this.ticketVisitService = ticketVisitService;
        this.objectMapper = objectMapper;
        this.auditPort = auditPort;
    }

    @Transactional
    public ChatToolCallEntity recordEvent(long sessionId, long messageId, String traceId,
                                          String toolCallId, String toolName, String eventStatus,
                                          Map<String, Object> arguments, boolean requiresConfirm,
                                          String resultSummary, String resultTicketId,
                                          String errorCode, long userId) {
        String status = "CONFIRM_REQUIRED".equals(eventStatus) ? "PENDING" : eventStatus;
        String argumentsJson = toJson(arguments);
        Map<String, Object> result = new LinkedHashMap<>();
        if (StringUtils.hasText(resultSummary)) {
            result.put("summary", resultSummary);
        }
        if (StringUtils.hasText(resultTicketId)) {
            result.put("ticketId", resultTicketId);
        }
        String resultJson = result.isEmpty() ? null : toJson(result);

        ChatToolCallEntity existing = toolCallMapper.selectOne(
                Wrappers.<ChatToolCallEntity>lambdaQuery()
                        .eq(ChatToolCallEntity::getTenantId, TENANT)
                        .eq(ChatToolCallEntity::getSessionId, sessionId)
                        .eq(ChatToolCallEntity::getToolCallId, toolCallId)
                        .last("LIMIT 1"));

        Long ticketId = parseLong(resultTicketId);
        if (existing != null) {
            existing.setMessageId(messageId);
            existing.setStatus(status);
            existing.setArgumentsJson(argumentsJson);
            existing.setResultJson(resultJson);
            existing.setResultTicketId(ticketId);
            existing.setErrorCode(errorCode);
            if (resultJson != null) {
                existing.setResultSizeBytes(resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
            }
            toolCallMapper.updateById(existing);
            return existing;
        }

        ChatToolCallEntity entity = new ChatToolCallEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setSessionId(sessionId);
        entity.setMessageId(messageId);
        entity.setToolCallId(toolCallId);
        entity.setToolName(toolName);
        entity.setArgumentsJson(argumentsJson);
        entity.setResultJson(resultJson);
        entity.setResultTicketId(ticketId);
        entity.setResultTruncated(0);
        entity.setResultSizeBytes(resultJson == null ? null
                : resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length);
        entity.setStatus(status);
        entity.setErrorCode(errorCode);
        entity.setTraceId(traceId);
        entity.setCreatedBy(userId);
        toolCallMapper.insert(entity);
        return entity;
    }

    public List<ChatToolCallView> listByMessage(long messageId) {
        return toViews(toolCallMapper.selectList(
                Wrappers.<ChatToolCallEntity>lambdaQuery()
                        .eq(ChatToolCallEntity::getTenantId, TENANT)
                        .eq(ChatToolCallEntity::getMessageId, messageId)
                        .orderByAsc(ChatToolCallEntity::getId)));
    }

    public Map<Long, List<ChatToolCallView>> viewsByMessageIds(List<Long> messageIds) {
        if (messageIds == null || messageIds.isEmpty()) {
            return Map.of();
        }
        List<ChatToolCallEntity> rows = toolCallMapper.selectList(
                Wrappers.<ChatToolCallEntity>lambdaQuery()
                        .eq(ChatToolCallEntity::getTenantId, TENANT)
                        .in(ChatToolCallEntity::getMessageId, messageIds)
                        .orderByAsc(ChatToolCallEntity::getId));
        return rows.stream()
                .collect(Collectors.groupingBy(ChatToolCallEntity::getMessageId,
                        Collectors.mapping(this::toView, Collectors.toList())));
    }

    @Transactional
    public TicketDetail confirm(long id, ConfirmToolCallRequest overrides, long userId,
                               List<String> roles) {
        ChatToolCallEntity entity = toolCallMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "工具调用不存在或无权访问");
        }
        ChatSessionEntity session = requireSession(entity.getSessionId(), userId);

        if ("SUCCESS".equals(entity.getStatus()) && entity.getResultTicketId() != null) {
            return ticketService.detail(entity.getResultTicketId());
        }
        if (!"PENDING".equals(entity.getStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "该工具调用不可确认");
        }
        if (!"ticket_create".equals(entity.getToolName())) {
            if (ACTION_TOOLS.contains(entity.getToolName())) {
                return confirmAction(entity, session, userId, roles);
            }
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "该工具调用不可确认");
        }

        Map<String, Object> args = fromJson(entity.getArgumentsJson());
        CreateTicketRequest request = buildTicketRequest(args, overrides);
        TicketDetail detail = ticketService.createFromTool(
                request, userId, "tool:" + entity.getToolCallId(), session.getId());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "已创建工单 " + detail.ticket().ticketNo());
        result.put("ticketId", detail.ticket().id());
        result.put("ticketNo", detail.ticket().ticketNo());
        String resultJson = toJson(result);
        toolCallMapper.update(null, Wrappers.<ChatToolCallEntity>lambdaUpdate()
                .eq(ChatToolCallEntity::getId, id)
                .set(ChatToolCallEntity::getStatus, "SUCCESS")
                .set(ChatToolCallEntity::getResultJson, resultJson)
                .set(ChatToolCallEntity::getResultTicketId, Long.parseLong(detail.ticket().id()))
                .set(ChatToolCallEntity::getResultSizeBytes,
                        resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .set(ChatToolCallEntity::getErrorCode, null));

        ChatSessionTicketEntity relation = new ChatSessionTicketEntity();
        relation.setId(IdWorker.getId());
        relation.setTenantId(TENANT);
        relation.setSessionId(session.getId());
        relation.setTicketId(Long.parseLong(detail.ticket().id()));
        relation.setRelationType("CREATED");
        relation.setCreatedToolCallId(entity.getToolCallId());
        relation.setCreatedBy(userId);
        try {
            sessionTicketMapper.insert(relation);
        } catch (DuplicateKeyException ignored) {
            // 并发确认幂等。
        }

        sessionMapper.update(null, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, session.getId())
                .set(ChatSessionEntity::getTicketId, Long.parseLong(detail.ticket().id()))
                .setSql("version = version + 1"));

        if (entity.getMessageId() != null) {
            triageRecordMapper.update(null, Wrappers.<TriageRecordEntity>lambdaUpdate()
                    .eq(TriageRecordEntity::getTenantId, TENANT)
                    .eq(TriageRecordEntity::getMessageId, entity.getMessageId())
                    .eq(TriageRecordEntity::getDraftStatus, "DRAFT")
                    .set(TriageRecordEntity::getDraftStatus, "CANCELLED"));
        }

        auditPort.record(new AuditEvent(
                userId, null, "TOOL_CONFIRM", "CHAT_TOOL_CALL", String.valueOf(id), null,
                Map.of("ticketId", detail.ticket().id(), "ticketNo", detail.ticket().ticketNo()),
                "SUCCESS", null));
        log.info("tool call confirmed, toolCall={}, ticket={}", entity.getToolCallId(), detail.ticket().ticketNo());
        return detail;
    }

    /**
     * 控制器用它做权限校验：按工具名返回所需权限，避免一律要求 ticket:create。
     */
    public String requiredPermission(long id, long userId) {
        ChatToolCallEntity entity = toolCallMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "工具调用不存在或无权访问");
        }
        requireSession(entity.getSessionId(), userId);
        return switch (entity.getToolName()) {
            case "ticket_create" -> "ticket:create";
            case "ticket_export", "ticket_comment_add" -> "ticket:read";
            case "ticket_assign" -> "ticket:assign";
            case "ticket_accept", "ticket_resolve", "ticket_hold", "ticket_resume",
                 "ticket_material_add" -> "ticket:handle";
            case "ticket_verify" -> "ticket:verify";
            case "ticket_close", "ticket_cancel", "ticket_finish",
                 "ticket_visit_add" -> "ticket:close";
            default -> null;
        };
    }

    private TicketDetail confirmAction(ChatToolCallEntity entity, ChatSessionEntity session,
                                       long userId, List<String> roles) {
        ChatToolCallEntity claimed = claimAction(entity);
        if (!"RUNNING".equals(claimed.getStatus())) {
            if ("SUCCESS".equals(claimed.getStatus()) && claimed.getResultTicketId() != null) {
                return ticketService.detail(claimed.getResultTicketId());
            }
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT,
                    "该操作正在执行或已完成，请刷新后重试");
        }
        Map<String, Object> args = fromJson(entity.getArgumentsJson());
        long ticketId = ticketService.resolveTicketId(text(args, "ticket_id"));
        String toolName = entity.getToolName();
        validateActionArgs(toolName, args);
        requireActionAccess(toolName, ticketId, userId, roles);

        String summary;
        switch (toolName) {
            case "ticket_comment_add" -> {
                ticketService.addComment(ticketId, text(args, "content"),
                        text(args, "visibility"), userId);
                summary = "已添加工单评论";
            }
            case "ticket_material_add" -> {
                ticketMaterialService.add(ticketId, new AddTicketMaterialRequest(
                        text(args, "material_name"), text(args, "specification"),
                        decimal(args.get("quantity")), text(args, "unit"),
                        decimal(args.get("amount")), text(args, "remark")), userId);
                summary = "已登记工单材料";
            }
            case "ticket_visit_add" -> {
                ticketVisitService.add(ticketId, new AddTicketVisitRequest(
                        boolObj(args.get("required")), text(args, "visit_method"),
                        text(args, "visited_at"), text(args, "visit_result"),
                        text(args, "status"), text(args, "remark")), userId);
                summary = "已登记回访";
            }
            default -> {
                String action = ACTION_BY_TOOL.get(toolName);
                Integer version = ticketService.detail(ticketId).ticket().version();
                if (version == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "无法获取工单版本");
                }
                TransitionRequest request = new TransitionRequest(
                        version,
                        parseLong(text(args, "assignee_id")),
                        boolObj(args.get("approved")),
                        text(args, "reason"),
                        text(args, "close_reason_code"),
                        text(args, "cancel_reason"),
                        text(args, "onsite_situation"),
                        text(args, "service_item_text"));
                ticketService.transition(ticketId, action, request, userId);
                summary = "已执行工单操作：" + action;
            }
        }

        markToolSuccess(entity, ticketId, summary);
        auditPort.record(new AuditEvent(
                userId, null, "TOOL_CONFIRM", "CHAT_TOOL_CALL",
                String.valueOf(entity.getId()), null,
                Map.of("tool", toolName, "ticketId", String.valueOf(ticketId)),
                "SUCCESS", null));
        log.info("tool action confirmed, tool={}, toolCall={}, ticket={}",
                toolName, entity.getToolCallId(), ticketId);
        return ticketService.detail(ticketId);
    }

    /**
     * 并发确认领取：PENDING → RUNNING 条件更新。
     * 同一事务内失败会回滚为 PENDING；成功则继续执行业务动作。
     */
    private ChatToolCallEntity claimAction(ChatToolCallEntity entity) {
        int claimed = toolCallMapper.update(null, Wrappers.<ChatToolCallEntity>lambdaUpdate()
                .eq(ChatToolCallEntity::getId, entity.getId())
                .eq(ChatToolCallEntity::getStatus, "PENDING")
                .set(ChatToolCallEntity::getStatus, "RUNNING"));
        if (claimed > 0) {
            entity.setStatus("RUNNING");
            return entity;
        }
        ChatToolCallEntity latest = toolCallMapper.selectById(entity.getId());
        return latest == null ? entity : latest;
    }

    /**
     * 防御性校验：Python 工具层已校验一次，这里保证即使参数被篡改也不会写坏数据。
     */
    private void validateActionArgs(String tool, Map<String, Object> args) {
        switch (tool) {
            case "ticket_assign" -> {
                if (parseLong(text(args, "assignee_id")) == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR,
                            "assignee_id 必须是数字用户 ID");
                }
            }
            case "ticket_resolve" -> {
                if (!StringUtils.hasText(text(args, "service_item_text"))) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "服务项目不能为空");
                }
            }
            case "ticket_verify" -> {
                if (boolObj(args.get("approved")) == null) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "approved 必须是布尔值");
                }
            }
            case "ticket_cancel" -> {
                if (!StringUtils.hasText(text(args, "cancel_reason"))) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "取消原因不能为空");
                }
            }
            case "ticket_hold" -> {
                if (!StringUtils.hasText(text(args, "reason"))) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "挂起原因不能为空");
                }
            }
            case "ticket_comment_add" -> {
                if (!StringUtils.hasText(text(args, "content"))) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "评论内容不能为空");
                }
            }
            case "ticket_material_add" -> {
                if (!StringUtils.hasText(text(args, "material_name"))) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "材料名称不能为空");
                }
                BigDecimal quantity = decimal(args.get("quantity"));
                BigDecimal amount = decimal(args.get("amount"));
                if (quantity != null && quantity.signum() < 0) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "数量不能为负数");
                }
                if (amount != null && amount.signum() < 0) {
                    throw new ApiException(ErrorCode.VALIDATION_ERROR, "金额不能为负数");
                }
            }
            default -> {
                // 其余动作由业务状态机与 TicketAccessService 校验。
            }
        }
    }

    private void requireActionAccess(String tool, long ticketId, long userId, List<String> roles) {
        switch (tool) {
            case "ticket_accept" ->
                    ticketAccessService.requireHandlerOrUnassigned(ticketId, userId, roles);
            case "ticket_resolve", "ticket_hold", "ticket_resume" ->
                    ticketAccessService.requireHandler(ticketId, userId, roles);
            case "ticket_verify" ->
                    ticketAccessService.requireReporterOrPrivileged(ticketId, userId, roles);
            default -> ticketAccessService.requireReadable(ticketId, userId, roles);
        }
    }

    private void markToolSuccess(ChatToolCallEntity entity, long ticketId, String summary) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", summary);
        result.put("ticketId", String.valueOf(ticketId));
        String resultJson = toJson(result);
        toolCallMapper.update(null, Wrappers.<ChatToolCallEntity>lambdaUpdate()
                .eq(ChatToolCallEntity::getId, entity.getId())
                .set(ChatToolCallEntity::getStatus, "SUCCESS")
                .set(ChatToolCallEntity::getResultJson, resultJson)
                .set(ChatToolCallEntity::getResultTicketId, ticketId)
                .set(ChatToolCallEntity::getResultSizeBytes,
                        resultJson == null ? null
                                : resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .set(ChatToolCallEntity::getErrorCode, null));
    }

    private BigDecimal decimal(Object value) {
        if (value == null || !StringUtils.hasText(String.valueOf(value))) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Boolean boolObj(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim().toLowerCase();
        if ("true".equals(text) || "1".equals(text) || "yes".equals(text)) {
            return true;
        }
        if ("false".equals(text) || "0".equals(text) || "no".equals(text)) {
            return false;
        }
        return null;
    }

    /**
     * 确认 AI 导出草稿：由 Java 生成 Excel、写入 MinIO exports/ 并返回短时效 URL。
     */
    @Transactional
    public TicketExportDownloadView confirmExport(long id, long userId, List<String> roles) {
        ChatToolCallEntity entity = toolCallMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "工具调用不存在或无权访问");
        }
        requireSession(entity.getSessionId(), userId);
        if (!"PENDING".equals(entity.getStatus()) || !"ticket_export".equals(entity.getToolName())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "该工具调用不可确认导出");
        }
        Map<String, Object> args = fromJson(entity.getArgumentsJson());
        boolean privileged = isPrivileged(roles);
        Long scopeUserId = privileged ? null : userId;
        TicketExportDownloadView view = ticketExportJobService.export(
                text(args, "status"),
                text(args, "keyword"),
                text(args, "priority_code"),
                text(args, "ticket_type_code"),
                text(args, "location"),
                null,
                scopeUserId,
                parseDbLocalTime(text(args, "start_time")),
                parseDbLocalTime(text(args, "end_time")));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("summary", "已生成导出文件 " + view.filename() + "，共 " + view.rowCount() + " 条");
        result.put("url", view.url());
        result.put("filename", view.filename());
        result.put("rowCount", view.rowCount());
        String resultJson = toJson(result);
        toolCallMapper.update(null, Wrappers.<ChatToolCallEntity>lambdaUpdate()
                .eq(ChatToolCallEntity::getId, id)
                .set(ChatToolCallEntity::getStatus, "SUCCESS")
                .set(ChatToolCallEntity::getResultJson, resultJson)
                .set(ChatToolCallEntity::getResultSizeBytes,
                        resultJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .set(ChatToolCallEntity::getErrorCode, null));

        auditPort.record(new AuditEvent(
                userId, null, "TOOL_EXPORT_CONFIRM", "CHAT_TOOL_CALL", String.valueOf(id), null,
                Map.of("filename", view.filename(), "rowCount", view.rowCount()),
                "SUCCESS", null));
        log.info("tool export confirmed, toolCall={}, filename={}, rows={}",
                entity.getToolCallId(), view.filename(), view.rowCount());
        return view;
    }

    private boolean isPrivileged(List<String> roles) {
        if (roles == null) {
            return false;
        }
        return roles.contains("ADMIN") || roles.contains("DISPATCHER")
                || roles.contains("ENGINEER") || roles.contains("INSPECTOR");
    }

    private LocalDateTime parseDbLocalTime(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value)
                    .atZoneSameInstant(ZoneId.of("Asia/Shanghai"))
                    .toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private CreateTicketRequest buildTicketRequest(Map<String, Object> args, ConfirmToolCallRequest overrides) {
        String title = firstText(overrides == null ? null : overrides.title(), text(args, "title"));
        String description = firstText(overrides == null ? null : overrides.description(),
                text(args, "description"));
        String ticketType = firstText(overrides == null ? null : overrides.ticketTypeCode(),
                text(args, "ticket_type_code"));
        String priority = firstText(overrides == null ? null : overrides.priorityCode(),
                text(args, "priority_code"));
        String location = firstText(overrides == null ? null : overrides.locationText(),
                text(args, "location_text"));
        if (!StringUtils.hasText(title)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "工单标题不能为空");
        }
        return new CreateTicketRequest(
                title.trim(),
                StringUtils.hasText(description) ? description.trim() : null,
                StringUtils.hasText(ticketType) ? ticketType.trim().toUpperCase() : "GENERAL",
                StringUtils.hasText(priority) ? priority.trim().toUpperCase() : "NORMAL",
                StringUtils.hasText(location) ? location.trim() : null,
                null, null, null,
                null, null, null, null, null, null, null, null, null, null
        );
    }

    private ChatToolCallView toView(ChatToolCallEntity entity) {
        Map<String, Object> arguments = fromJson(entity.getArgumentsJson());
        Map<String, Object> result = fromJson(entity.getResultJson());
        boolean requiresConfirm = ("ticket_create".equals(entity.getToolName())
                || "ticket_export".equals(entity.getToolName())
                || ACTION_TOOLS.contains(entity.getToolName()))
                && "PENDING".equals(entity.getStatus());
        return new ChatToolCallView(
                String.valueOf(entity.getId()),
                entity.getToolCallId(),
                entity.getToolName(),
                entity.getStatus(),
                requiresConfirm,
                arguments,
                summarize(entity.getToolName(), arguments),
                result.get("summary") == null ? null : String.valueOf(result.get("summary")),
                entity.getResultTicketId() == null ? null : String.valueOf(entity.getResultTicketId()),
                result.get("url") == null ? null : String.valueOf(result.get("url")),
                result.get("filename") == null ? null : String.valueOf(result.get("filename")),
                result.get("rowCount") instanceof Number number ? number.intValue() : null,
                entity.getErrorCode()
        );
    }

    private List<ChatToolCallView> toViews(List<ChatToolCallEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<ChatToolCallView> views = new ArrayList<>(rows.size());
        for (ChatToolCallEntity row : rows) {
            views.add(toView(row));
        }
        return views;
    }

    private String summarize(String name, Map<String, Object> args) {
        if ("ticket_query".equals(name)) {
            return "状态=" + (args.get("status") == null ? "全部" : args.get("status"))
                    + "，关键词=" + (args.get("keyword") == null ? "无" : args.get("keyword"))
                    + "，条数=" + (args.get("limit") == null ? 5 : args.get("limit"));
        }
        if ("ticket_detail".equals(name)) {
            return "工单=" + (args.get("ticket_id") == null ? "未指定" : args.get("ticket_id"));
        }
        if ("ticket_create".equals(name)) {
            String title = args.get("title") == null ? "" : String.valueOf(args.get("title"));
            return "标题=" + (title.length() > 40 ? title.substring(0, 40) : title);
        }
        if ("ticket_export".equals(name)) {
            return "范围=" + (args.get("start_time") == null ? "?" : args.get("start_time"))
                    + " ~ " + (args.get("end_time") == null ? "?" : args.get("end_time"));
        }
        if (ACTION_TOOLS.contains(name)) {
            String ticket = args.get("ticket_id") == null ? "未指定" : String.valueOf(args.get("ticket_id"));
            return "工单=" + ticket + "，操作=" + name.replace("ticket_", "");
        }
        return "";
    }

    private ChatSessionEntity requireSession(long sessionId, long userId) {
        ChatSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null || session.getDeletedAt() != null
                || !Objects.equals(session.getUserId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "会话不存在或无权访问");
        }
        return session;
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private Long parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }
}
