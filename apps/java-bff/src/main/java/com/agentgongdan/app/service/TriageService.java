package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.ChatSessionEntity;
import com.agentgongdan.adapters.persistence.entity.ChatSessionTicketEntity;
import com.agentgongdan.adapters.persistence.entity.ChatToolCallEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.entity.TriageRecordEntity;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatSessionTicketMapper;
import com.agentgongdan.adapters.persistence.mapper.ChatToolCallMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.adapters.persistence.mapper.TriageRecordMapper;
import com.agentgongdan.adapters.web.dto.ConfirmTriageRequest;
import com.agentgongdan.adapters.web.dto.CreateTicketRequest;
import com.agentgongdan.adapters.web.dto.TicketDetail;
import com.agentgongdan.adapters.web.dto.TriageRecordView;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.agentgongdan.domain.triage.TriageResult;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 分诊落库与草稿确认（人在回路）。
 */
@Service
public class TriageService {

    private static final Logger log = LoggerFactory.getLogger(TriageService.class);
    private static final long TENANT = 0L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final TriageRecordMapper triageMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatSessionTicketMapper sessionTicketMapper;
    private final ChatToolCallMapper toolCallMapper;
    private final TicketMapper ticketMapper;
    private final TicketService ticketService;
    private final ObjectMapper objectMapper;
    private final AuditPort auditPort;
    private final double confidenceThreshold;
    private final long draftTtlHours;

    public TriageService(TriageRecordMapper triageMapper,
                         ChatSessionMapper sessionMapper,
                         ChatSessionTicketMapper sessionTicketMapper,
                         ChatToolCallMapper toolCallMapper,
                         TicketMapper ticketMapper,
                         TicketService ticketService,
                         ObjectMapper objectMapper,
                         AuditPort auditPort,
                         @Value("${TRIAGE_CONFIDENCE_THRESHOLD:0.75}") double confidenceThreshold,
                         @Value("${TRIAGE_DRAFT_TTL_HOURS:24}") long draftTtlHours) {
        this.triageMapper = triageMapper;
        this.sessionMapper = sessionMapper;
        this.sessionTicketMapper = sessionTicketMapper;
        this.toolCallMapper = toolCallMapper;
        this.ticketMapper = ticketMapper;
        this.ticketService = ticketService;
        this.objectMapper = objectMapper;
        this.auditPort = auditPort;
        this.confidenceThreshold = confidenceThreshold;
        this.draftTtlHours = draftTtlHours;
    }

    @Transactional
    public long record(long sessionId, long messageId, String traceId, TriageResult result, long userId) {
        TriageRecordEntity entity = new TriageRecordEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setSessionId(sessionId);
        entity.setMessageId(messageId);
        entity.setTraceId(traceId);
        entity.setIntentCode(result.intentCode());
        entity.setEntitiesJson(toJson(result.entities() == null ? Map.of() : result.entities()));
        entity.setConfidence(BigDecimal.valueOf(result.confidence()).setScale(4, RoundingMode.HALF_UP));
        entity.setThreshold(BigDecimal.valueOf(confidenceThreshold).setScale(4, RoundingMode.HALF_UP));
        entity.setAction(result.action());
        entity.setLowConfidence(result.lowConfidence() ? 1 : 0);
        entity.setClarifyRound(0);
        if (result.draft() != null && !result.draft().isEmpty()) {
            entity.setDraftJson(toJson(result.draft()));
            entity.setDraftStatus("DRAFT");
        }
        entity.setModel(result.model());
        entity.setPromptVersion(result.promptVersion());
        entity.setLatencyMs((int) Math.min(result.latencyMs(), Integer.MAX_VALUE));
        entity.setCreatedBy(userId);
        triageMapper.insert(entity);
        return entity.getId();
    }

    public List<TriageRecordView> listBySession(long sessionId, long userId) {
        requireSession(sessionId, userId);
        return toViews(triageMapper.selectList(
                Wrappers.<TriageRecordEntity>lambdaQuery()
                        .eq(TriageRecordEntity::getTenantId, TENANT)
                        .eq(TriageRecordEntity::getSessionId, sessionId)
                        .orderByAsc(TriageRecordEntity::getId)));
    }

    public List<TriageRecordView> listByMessage(long messageId) {
        return toViews(triageMapper.selectList(
                Wrappers.<TriageRecordEntity>lambdaQuery()
                        .eq(TriageRecordEntity::getTenantId, TENANT)
                        .eq(TriageRecordEntity::getMessageId, messageId)
                        .orderByAsc(TriageRecordEntity::getId)));
    }

    private List<TriageRecordView> toViews(List<TriageRecordEntity> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        List<Long> triageIds = records.stream().map(TriageRecordEntity::getId).toList();
        Map<Long, TicketEntity> tickets = ticketMapper.selectList(
                        Wrappers.<TicketEntity>lambdaQuery()
                                .eq(TicketEntity::getTenantId, TENANT)
                                .in(TicketEntity::getTriageId, triageIds)
                                .isNull(TicketEntity::getDeletedAt))
                .stream()
                .collect(Collectors.toMap(TicketEntity::getTriageId, t -> t, (a, b) -> a));

        return records.stream().map(record -> {
            TicketEntity ticket = tickets.get(record.getId());
            return new TriageRecordView(
                    String.valueOf(record.getId()),
                    record.getMessageId() == null ? null : String.valueOf(record.getMessageId()),
                    record.getIntentCode(),
                    fromJson(record.getEntitiesJson()),
                    record.getConfidence() == null ? null : record.getConfidence().doubleValue(),
                    record.getAction(),
                    record.getLowConfidence() != null && record.getLowConfidence() == 1,
                    fromJson(record.getDraftJson()),
                    record.getDraftStatus(),
                    record.getModel(),
                    formatTime(record.getCreatedAt()),
                    formatTime(record.getConfirmedAt()),
                    ticket == null ? null : String.valueOf(ticket.getId()),
                    ticket == null ? null : ticket.getTicketNo(),
                    ticket == null ? null : ticket.getStatusCode()
            );
        }).toList();
    }

    @Transactional
    public TicketDetail confirm(long triageId, ConfirmTriageRequest overrides, long userId) {
        TriageRecordEntity record = triageMapper.selectById(triageId);
        if (record == null) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "分诊记录不存在或无权访问");
        }
        ChatSessionEntity session = requireSession(record.getSessionId(), userId);

        if ("CONFIRMED".equals(record.getDraftStatus())) {
            TicketEntity existing = findTicketByTriage(triageId);
            if (existing != null) {
                return ticketService.detail(existing.getId());
            }
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "分诊草稿已确认，但未找到对应工单");
        }
        if (!"DRAFT".equals(record.getDraftStatus())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "分诊草稿不可确认");
        }
        if (record.getCreatedAt() != null
                && record.getCreatedAt().plusHours(draftTtlHours).isBefore(LocalDateTime.now(ZoneOffset.UTC))) {
            triageMapper.update(null, Wrappers.<TriageRecordEntity>lambdaUpdate()
                    .eq(TriageRecordEntity::getId, triageId)
                    .set(TriageRecordEntity::getDraftStatus, "EXPIRED"));
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "分诊草稿已过期，请重新描述问题");
        }

        Map<String, Object> draft = fromJson(record.getDraftJson());
        CreateTicketRequest request = buildTicketRequest(draft, overrides);
        TicketDetail detail = ticketService.createFromChat(
                request, userId, "triage:" + triageId, session.getId(), triageId);

        triageMapper.update(null, Wrappers.<TriageRecordEntity>lambdaUpdate()
                .eq(TriageRecordEntity::getId, triageId)
                .set(TriageRecordEntity::getDraftStatus, "CONFIRMED")
                .set(TriageRecordEntity::getConfirmedBy, userId)
                .set(TriageRecordEntity::getConfirmedAt, LocalDateTime.now(ZoneOffset.UTC)));

        ChatSessionTicketEntity relation = new ChatSessionTicketEntity();
        relation.setId(IdWorker.getId());
        relation.setTenantId(TENANT);
        relation.setSessionId(session.getId());
        relation.setTicketId(Long.parseLong(detail.ticket().id()));
        relation.setRelationType("CREATED");
        relation.setCreatedBy(userId);
        try {
            sessionTicketMapper.insert(relation);
        } catch (DuplicateKeyException ignored) {
            // 并发确认时关系已存在，保持幂等。
        }

        sessionMapper.update(null, Wrappers.<ChatSessionEntity>lambdaUpdate()
                .eq(ChatSessionEntity::getId, session.getId())
                .set(ChatSessionEntity::getTicketId, Long.parseLong(detail.ticket().id()))
                .setSql("version = version + 1"));

        if (record.getMessageId() != null) {
            toolCallMapper.update(null, Wrappers.<ChatToolCallEntity>lambdaUpdate()
                    .eq(ChatToolCallEntity::getTenantId, TENANT)
                    .eq(ChatToolCallEntity::getMessageId, record.getMessageId())
                    .eq(ChatToolCallEntity::getToolName, "ticket_create")
                    .eq(ChatToolCallEntity::getStatus, "PENDING")
                    .set(ChatToolCallEntity::getStatus, "CANCELLED"));
        }

        auditPort.record(new AuditEvent(
                userId, null, "TRIAGE_CONFIRM", "TRIAGE",
                String.valueOf(triageId), null,
                Map.of("ticketId", detail.ticket().id(), "ticketNo", detail.ticket().ticketNo()),
                "SUCCESS", null));
        log.info("triage confirmed, triage={}, ticket={}", triageId, detail.ticket().ticketNo());
        return detail;
    }

    private CreateTicketRequest buildTicketRequest(Map<String, Object> draft, ConfirmTriageRequest overrides) {
        String title = firstText(overrides == null ? null : overrides.title(), text(draft, "title"));
        String description = firstText(overrides == null ? null : overrides.description(),
                text(draft, "description"));
        String ticketType = firstText(overrides == null ? null : overrides.ticketTypeCode(),
                text(draft, "ticket_type_code"));
        String priority = firstText(overrides == null ? null : overrides.priorityCode(),
                text(draft, "priority_code"));
        String location = firstText(overrides == null ? null : overrides.locationText(),
                text(draft, "location_text"));
        if (!StringUtils.hasText(title)) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, "工单标题不能为空");
        }
        return new CreateTicketRequest(
                title.trim(),
                StringUtils.hasText(description) ? description.trim() : null,
                StringUtils.hasText(ticketType) ? ticketType.trim().toUpperCase() : "GENERAL",
                StringUtils.hasText(priority) ? priority.trim().toUpperCase() : "NORMAL",
                StringUtils.hasText(location) ? location.trim() : null,
                overrides == null ? null : overrides.customerId(),
                overrides == null ? null : overrides.roomId(),
                overrides == null ? null : overrides.equipmentId(),
                null, null, null, null, null, null, null, null
        );
    }

    private ChatSessionEntity requireSession(long sessionId, long userId) {
        ChatSessionEntity session = sessionMapper.selectById(sessionId);
        if (session == null || session.getDeletedAt() != null
                || !Objects.equals(session.getUserId(), userId)) {
            throw new ApiException(ErrorCode.AUTH_FORBIDDEN, "会话不存在或无权访问");
        }
        return session;
    }

    private TicketEntity findTicketByTriage(long triageId) {
        return ticketMapper.selectOne(Wrappers.<TicketEntity>lambdaQuery()
                .eq(TicketEntity::getTenantId, TENANT)
                .eq(TicketEntity::getTriageId, triageId)
                .isNull(TicketEntity::getDeletedAt)
                .last("LIMIT 1"));
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
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
            return new HashMap<>();
        }
    }

    private String formatTime(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
