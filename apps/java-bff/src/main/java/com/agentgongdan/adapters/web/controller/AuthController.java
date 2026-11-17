package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.web.dto.LoginRequest;
import com.agentgongdan.adapters.web.dto.LoginResponse;
import com.agentgongdan.adapters.web.dto.MeResponse;
import com.agentgongdan.adapters.web.dto.UserView;
import com.agentgongdan.app.dto.AuthResult;
import com.agentgongdan.app.service.AuthService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AuthResult result = authService.login(request.username(), request.password());
        return ApiResponse.ok(new LoginResponse(
                result.accessToken(),
                result.expiresIn(),
                "Bearer",
                new UserView(result.userId(), result.username(), result.displayName()),
                result.roles(),
                result.permissions()
        ));
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        AuthResult result = authService.me();
        return ApiResponse.ok(new MeResponse(
                new UserView(result.userId(), result.username(), result.displayName()),
                result.roles(),
                result.permissions()
        ));
    }
}
