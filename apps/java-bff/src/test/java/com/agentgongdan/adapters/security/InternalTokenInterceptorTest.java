package com.agentgongdan.adapters.security;

import com.agentgongdan.app.ports.security.DelegationClaims;
import com.agentgongdan.app.ports.security.DelegationTokenPort;
import com.agentgongdan.common.error.ApiException;
import com.agentgongdan.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * /agent/** 鉴权契约：内部令牌必需；委托令牌优先且不可被伪造 Actor 头覆盖。
 */
class InternalTokenInterceptorTest {

    private static final String TOKEN = "internal-token-for-test";

    private DelegationTokenPort delegationTokenPort;
    private InternalTokenInterceptor interceptor;

    @BeforeEach
    void setUp() {
        delegationTokenPort = mock(DelegationTokenPort.class);
        interceptor = new InternalTokenInterceptor(TOKEN, "", delegationTokenPort);
    }

    @Test
    @DisplayName("缺少或错误内部令牌返回 10001")
    void requiresInternalToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);

        request.addHeader("X-Internal-Token", "wrong");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_UNAUTHORIZED);
    }

    @Test
    @DisplayName("Actor 与角色从请求头解析")
    void parsesActorHeaders() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", TOKEN);
        request.addHeader("X-Actor-Id", "10001");
        request.addHeader("X-Actor-Roles", "ADMIN, ENGINEER");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        assertThat(request.getAttribute("internalActorId")).isEqualTo(10001L);
        assertThat(request.getAttribute("internalActorRoles")).isEqualTo(List.of("ADMIN", "ENGINEER"));
    }

    @Test
    @DisplayName("委托令牌优先：令牌声明覆盖可伪造的 X-Actor-*")
    void delegationOverridesActorHeaders() {
        DelegationClaims claims = new DelegationClaims("delegation-1", 10003L,
                List.of("REPORTER"), List.of("ticket.query"), 0, Long.MAX_VALUE);
        when(delegationTokenPort.verify("delegation-token")).thenReturn(claims);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Internal-Token", TOKEN);
        request.addHeader("X-Delegation-Token", "delegation-token");
        request.addHeader("X-Actor-Id", "10001");
        request.addHeader("X-Actor-Roles", "ADMIN");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        assertThat(request.getAttribute("internalActorId")).isEqualTo(10003L);
        assertThat(request.getAttribute("internalActorRoles")).isEqualTo(List.of("REPORTER"));
        assertThat(request.getAttribute("internalDelegationScopes")).isEqualTo(List.of("ticket.query"));
        assertThat(request.getAttribute("internalDelegationId")).isEqualTo("delegation-1");
    }
}
