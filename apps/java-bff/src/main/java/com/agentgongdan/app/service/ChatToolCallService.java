package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.ChatSessionEntity;
import com.agentgongdan.adapters.persistence.entity.ChatSessionTicketEntity;
import com.agentgongdan.adapters.persistence.entity.ChatToolCallEntity;
import com.agentgongdan.adapters.persistence.entity.TriageRecordEntity;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionTicketMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatToolCallMapper;
import com.agentgongdan.adapters.persistence.mapper.TriageRecordMapper;
import com.agentgongdan.adapters.web.dto.ChatToolCallView;
import com.agentgongdan.adapters.web.dto.ConfirmToolCallRequest;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TicketExportDownloadView;
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

    private final ChatToolCallMapper toolCallMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatSessionTicketMapper sessionTicketMapper;
    private final TriageRecordMapper triageRecordMapper;
    private final TicketService ticketService;
    private final TicketExportJobService ticketExportJobService;
    private final ObjectMapper objectMapper;
    private final AuditPort auditPort;

    public ChatToolCallService(ChatToolCallMapper toolCallMapper,
                               ChatSessionMapper sessionMapper,
                               ChatSessionTicketMapper sessionTicketMapper,
                               TriageRecordMapper triageRecordMapper,
                               TicketService ticketService,
                               TicketExportJobService ticketExportJobService,
                               ObjectMapper objectMapper,
                               AuditPort auditPort) {
        this.toolCallMapper = toolCallMapper;
        this.sessionMapper = sessionMapper;
        this.sessionTicketMapper = sessionTicketMapper;
        this.triageRecordMapper = triageRecordMapper;
        this.ticketService = ticketService;
        this.ticketExportJobService = ticketExportJobService;
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
    public TicketDetail confirm(long id, ConfirmToolCallRequest overrides, long userId) {
        ChatToolCallEntity entity = toolCallMapper.selectById(id);
        if (entity == null) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "工具调用不存在或无权访问");
        }
        ChatSessionEntity session = requireSession(entity.getSessionId(), userId);

        if ("SUCCESS".equals(entity.getStatus()) && entity.getResultTicketId() != null) {
            return ticketService.detail(entity.getResultTicketId());
        }
        if (!"PENDING".equals(entity.getStatus()) || !"ticket_create".equals(entity.getToolName())) {
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
                null, null, null, null, null, null, null, null
        );
    }

    private ChatToolCallView toView(ChatToolCallEntity entity) {
        Map<String, Object> arguments = fromJson(entity.getArgumentsJson());
        Map<String, Object> result = fromJson(entity.getResultJson());
        boolean requiresConfirm = ("ticket_create".equals(entity.getToolName())
                || "ticket_export".equals(entity.getToolName()))
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
