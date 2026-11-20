package com.agentgongdan.config;

import com.agentgongdan.AgentGongdanApplication;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.DefaultBootstrapContext;

import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 端口守卫：必需环境变量与占用检测。
 */
class AgentStandalonePortGuardTest {

    @Test
    @DisplayName("agent Profile 缺少 AGENT_SERVICE_PORT 时启动失败")
    void missingPortFailsFast() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("agent");

        assertThatThrownBy(() -> guard().onApplicationEvent(event(environment)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AGENT_SERVICE_PORT");
    }

    @Test
    @DisplayName("端口被占用时启动失败，不尝试清理")
    void occupiedPortFailsFast() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            StandardEnvironment environment = new StandardEnvironment();
            environment.setActiveProfiles("agent");
            environment.getPropertySources().addFirst(new MapPropertySource("test",
                    java.util.Map.of("server.port", String.valueOf(occupied.getLocalPort()))));

            assertThatThrownBy(() -> guard().onApplicationEvent(event(environment)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("不可用");
        }
    }

    @Test
    @DisplayName("非 agent Profile 不做端口守卫")
    void nonAgentProfileIgnored() {
        StandardEnvironment environment = new StandardEnvironment();

        assertThatNoException().isThrownBy(() -> guard().onApplicationEvent(event(environment)));
    }

    private AgentStandalonePortGuard guard() {
        return new AgentStandalonePortGuard();
    }

    private ApplicationEnvironmentPreparedEvent event(StandardEnvironment environment) {
        return new ApplicationEnvironmentPreparedEvent(new DefaultBootstrapContext(),
                new SpringApplication(AgentGongdanApplication.class), new String[0], environment);
    }
}
