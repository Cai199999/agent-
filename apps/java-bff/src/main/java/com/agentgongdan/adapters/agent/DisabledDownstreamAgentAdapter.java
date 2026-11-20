package com.agentgongdan.adapters.agent;

import com.agentgongdan.app.ports.agent.AgentInvocation;
import com.agentgongdan.app.ports.agent.AgentInvocationResult;
import com.agentgongdan.app.ports.agent.DownstreamAgentPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 下游 Agent 适配器占位：默认禁用，仅用于冻结调用契约。
 * 启用真实调用前必须补齐：目标白名单、委托令牌签发、超时/熔断与审计。
 */
@Component
public class DisabledDownstreamAgentAdapter implements DownstreamAgentPort {

    private final boolean enabled;

    public DisabledDownstreamAgentAdapter(
            @Value("${agent.downstream.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public AgentInvocationResult invoke(AgentInvocation invocation) {
        if (!enabled) {
            throw new ApiException(ErrorCode.SYS_DEPENDENCY_UNAVAILABLE,
                    "下游 Agent 调用未启用");
        }
        throw new ApiException(ErrorCode.SYS_DEPENDENCY_UNAVAILABLE,
                "下游 Agent 适配器尚未实现");
    }
}
