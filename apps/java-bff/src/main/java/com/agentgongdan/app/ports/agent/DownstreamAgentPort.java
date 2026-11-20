package com.agentgongdan.app.ports.agent;

/**
 * 下游 Agent 调用端口（预留）。
 * <p>当前仅定义契约与禁用适配器，不做真实网络调用；未来接入其他 Agent 时，
 * 适配器负责：目标白名单、委托令牌签发、超时/熔断、结果与错误码映射。</p>
 */
public interface DownstreamAgentPort {

    boolean enabled();

    AgentInvocationResult invoke(AgentInvocation invocation);
}
