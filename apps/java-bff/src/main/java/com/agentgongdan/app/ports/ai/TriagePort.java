package com.agentgongdan.app.ports.ai;

import com.agentgongdan.domain.chat.ChatTurn;
import com.agentgongdan.domain.triage.TriageResult;

import java.util.List;

public interface TriagePort {

    TriageResult triage(String traceId, List<ChatTurn> messages);
}
