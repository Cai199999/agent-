package com.agentgongdan.app.ports.audit;

import com.agentgongdan.domain.audit.AuditEvent;

public interface AuditPort {

    void record(AuditEvent event);
}
