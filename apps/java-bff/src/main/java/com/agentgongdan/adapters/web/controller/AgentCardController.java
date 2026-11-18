package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.app.service.AgentTaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * A2A 风格 Agent Card 发现端点；只暴露能力，不暴露密钥。
 */
@RestController
public class AgentCardController {

    private final AgentTaskService agentTaskService;

    public AgentCardController(AgentTaskService agentTaskService) {
        this.agentTaskService = agentTaskService;
    }

    @GetMapping({"/.well-known/agent-card.json", "/agent-card.json"})
    public Map<String, Object> card() {
        return agentTaskService.agentCard();
    }
}
