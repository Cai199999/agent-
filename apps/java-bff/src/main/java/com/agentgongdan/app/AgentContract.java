package com.agentgongdan.app;

/**
 * 子 Agent 对外契约常量；Manifest、Agent Card 与健康检查共用以避免版本漂移。
 */
public final class AgentContract {

    public static final String AGENT_ID = "property-maintenance";
    public static final String VERSION = "0.8.0";
    public static final String PROTOCOL = "agent-task-v1";

    private AgentContract() {
    }
}
