package com.agentgongdan.app.ports.security;

public record IssuedDelegation(
        String token,
        DelegationClaims claims
) {
}
