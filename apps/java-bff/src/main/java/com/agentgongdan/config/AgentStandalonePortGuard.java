package com.agentgongdan.config;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.util.PlaceholderResolutionException;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Arrays;

/**
 * 独立 Agent 实例端口守卫：
 * 1. AGENT_SERVICE_PORT 必须显式配置（不再提供 8400 默认值）；
 * 2. 启动前探测端口是否可绑定，冲突时立即失败并提示更换端口。
 * <p>只对 agent Profile / agent.deployment.standalone=true 生效，普通 BFF 不受影响。
 * 发现冲突时绝不尝试清理占用者，符合共享主机边界约束。</p>
 */
public class AgentStandalonePortGuard
        implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        Environment environment = event.getEnvironment();
        if (!standalone(environment)) {
            return;
        }
        String rawPort;
        try {
            rawPort = environment.getProperty("server.port");
        } catch (PlaceholderResolutionException e) {
            // 未配置 AGENT_SERVICE_PORT 时，Spring 解析占位符会先抛异常；转换为可读提示。
            throw new IllegalStateException(
                    "Agent 独立实例必须显式配置 AGENT_SERVICE_PORT（本项目登记端口 8402）", e);
        }
        if (rawPort == null || rawPort.isBlank() || rawPort.startsWith("${")) {
            throw new IllegalStateException(
                    "Agent 独立实例必须显式配置 AGENT_SERVICE_PORT（本项目登记端口 8402）");
        }
        int port;
        try {
            port = Integer.parseInt(rawPort.trim());
        } catch (NumberFormatException e) {
            throw new IllegalStateException("AGENT_SERVICE_PORT 必须是整数: " + rawPort, e);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("AGENT_SERVICE_PORT 超出范围: " + port);
        }
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(port));
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Agent 端口 " + port + " 不可用（可能被其他项目占用）；"
                            + "请更换 AGENT_SERVICE_PORT，禁止清理占用者", e);
        }
    }

    private boolean standalone(Environment environment) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("agent")) {
            return true;
        }
        return Boolean.parseBoolean(
                environment.getProperty("agent.deployment.standalone", "false"));
    }
}
