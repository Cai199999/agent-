package com.agentgongdan.domain.audit;

import java.util.Map;

public record AuditEvent(
        Long actorId,
        String actorName,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> before,
        Map<String, Object> after,
        String result,
        String errorCode
) {
}
