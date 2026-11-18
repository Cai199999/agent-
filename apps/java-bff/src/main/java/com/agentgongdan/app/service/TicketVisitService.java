package com.agentgongdan.app.service;

import com.agentgongdan.adapters.persistence.entity.SysUserEntity;
import com.agentgongdan.adapters.persistence.entity.TicketEntity;
import com.agentgongdan.adapters.persistence.entity.TicketVisitEntity;
import com.agentgongdan.adapters.persistence.mapper.SysUserMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketMapper;
import com.agentgongdan.adapters.persistence.mapper.TicketVisitMapper;
import com.agentgongdan.adapters.web.dto.AddTicketVisitRequest;
import com.agentgongdan.adapters.web.dto.TicketVisitView;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class TicketVisitService {

    private static final long TENANT = 0L;

    private final TicketVisitMapper visitMapper;
    private final TicketMapper ticketMapper;
    private final SysUserMapper userMapper;
    private final AuditPort auditPort;

    public TicketVisitService(TicketVisitMapper visitMapper, TicketMapper ticketMapper,
                              SysUserMapper userMapper, AuditPort auditPort) {
        this.visitMapper = visitMapper;
        this.ticketMapper = ticketMapper;
        this.userMapper = userMapper;
        this.auditPort = auditPort;
    }

    public List<TicketVisitView> list(long ticketId) {
        List<TicketVisitEntity> rows = visitMapper.selectList(Wrappers.<TicketVisitEntity>lambdaQuery()
                .eq(TicketVisitEntity::getTenantId, TENANT)
                .eq(TicketVisitEntity::getTicketId, ticketId)
                .isNull(TicketVisitEntity::getDeletedAt)
                .orderByAsc(TicketVisitEntity::getId));
        Map<Long, String> names = rows.stream()
                .map(TicketVisitEntity::getVisitorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .collect(Collectors.toMap(id -> id, id -> {
                    SysUserEntity user = userMapper.selectById(id);
                    return user == null ? "" : user.getDisplayName();
                }));
        return rows.stream().map(row -> toView(row, names)).toList();
    }

    @Transactional
    public TicketVisitView add(long ticketId, AddTicketVisitRequest request, long userId) {
        TicketEntity ticket = ticketMapper.selectById(ticketId);
        if (ticket == null || ticket.getDeletedAt() != null) {
            throw new ApiException(ErrorCode.TICKET_NOT_FOUND);
        }
        if (!"CLOSED".equals(ticket.getStatusCode())) {
            throw new ApiException(ErrorCode.TICKET_STATE_CONFLICT, "仅已关闭工单可登记回访");
        }
        TicketVisitEntity entity = new TicketVisitEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(TENANT);
        entity.setTicketId(ticketId);
        entity.setRequiredFlag(Boolean.TRUE.equals(request.required()) ? 1 : 0);
        entity.setVisitMethod(trim(request.visitMethod()));
        entity.setVisitorId(userId);
        entity.setVisitedAt(parseUtc(request.visitedAt()));
        entity.setVisitResult(trim(request.visitResult()));
        entity.setStatus(StringUtils.hasText(request.status())
                ? request.status().trim().toUpperCase() : "DONE");
        entity.setRemark(trim(request.remark()));
        entity.setCreatedBy(userId);
        entity.setUpdatedBy(userId);
        visitMapper.insert(entity);
        auditPort.record(new AuditEvent(
                userId, null, "TICKET_VISIT_ADD", "TICKET", String.valueOf(ticketId),
                null, Map.of("visitId", String.valueOf(entity.getId())), "SUCCESS", null));
        SysUserEntity user = userMapper.selectById(userId);
        return toView(entity, Map.of(userId, user == null ? "" : user.getDisplayName()));
    }

    private TicketVisitView toView(TicketVisitEntity entity, Map<Long, String> names) {
        return new TicketVisitView(
                String.valueOf(entity.getId()),
                entity.getRequiredFlag() != null && entity.getRequiredFlag() == 1,
                entity.getVisitMethod(),
                entity.getVisitorId() == null ? null : String.valueOf(entity.getVisitorId()),
                names.get(entity.getVisitorId()),
                iso(entity.getVisitedAt()),
                entity.getVisitResult(),
                entity.getStatus(),
                entity.getRemark()
        );
    }

    private LocalDateTime parseUtc(String value) {
        if (!StringUtils.hasText(value)) {
            return LocalDateTime.now(ZoneOffset.UTC);
        }
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (Exception ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (Exception e) {
                return LocalDateTime.now(ZoneOffset.UTC);
            }
        }
    }

    private String trim(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String iso(LocalDateTime value) {
        return value == null ? null
                : value.atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }
}
