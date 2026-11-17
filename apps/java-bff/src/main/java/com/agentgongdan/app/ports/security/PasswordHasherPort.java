package com.agentgongdan.app.ports.security;

public interface PasswordHasherPort {

    boolean matches(String rawPassword, String encodedPassword);
}
