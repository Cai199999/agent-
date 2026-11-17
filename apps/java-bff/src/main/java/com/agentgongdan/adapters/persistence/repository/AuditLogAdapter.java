package com.agentgongdan.adapters.persistence.repository;

import com.agentgongdan.adapters.persistence.entity.SysAuditLogEntity;
import com.agentgongdan.adapters.persistence.mapper.SysAuditLogMapper;
import com.agentgongdan.app.ports.audit.AuditPort;
import com.agentgongdan.domain.audit.AuditEvent;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Repository;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Repository
public class AuditLogAdapter implements AuditPort {

    private static final Logger log = LoggerFactory.getLogger(AuditLogAdapter.class);

    private final SysAuditLogMapper auditLogMapper;
    private final ObjectMapper objectMapper;

    public AuditLogAdapter(SysAuditLogMapper auditLogMapper, ObjectMapper objectMapper) {
        this.auditLogMapper = auditLogMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public void record(AuditEvent event) {
        SysAuditLogEntity entity = new SysAuditLogEntity();
        entity.setId(IdWorker.getId());
        entity.setTenantId(0L);
        entity.setTraceId(MDC.get("traceId"));
        entity.setActorId(event.actorId());
        entity.setActorName(event.actorName());
        entity.setAction(event.action());
        entity.setResourceType(event.resourceType());
        entity.setResourceId(event.resourceId());
        entity.setBeforeJson(toJson(event.before()));
        entity.setAfterJson(toJson(event.after()));
        entity.setIp(remoteIp());
        entity.setUserAgent(userAgent());
        entity.setResult(event.result() == null ? "SUCCESS" : event.result());
        entity.setErrorCode(event.errorCode());
        entity.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC));
        entity.setCreatedBy(event.actorId());
        auditLogMapper.insert(entity);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("audit json serialize failed", e);
            return null;
        }
    }

    private HttpServletRequest currentRequest() {
        var attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servlet
                ? servlet.getRequest() : null;
    }

    private String remoteIp() {
        HttpServletRequest request = currentRequest();
        return request == null ? null : request.getRemoteAddr();
    }

    private String userAgent() {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return null;
        }
        String value = request.getHeader("User-Agent");
        return value == null || value.length() <= 512 ? value : value.substring(0, 512);
    }
}
