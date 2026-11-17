package com.agentgongdan.adapters.security;

import com.agentgongdan.app.ports.security.PasswordHasherPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptPasswordHasherAdapter implements PasswordHasherPort {

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    @Override
    public boolean matches(String rawPassword, String encodedPassword) {
        return encodedPassword != null && encoder.matches(rawPassword, encodedPassword);
    }
}
