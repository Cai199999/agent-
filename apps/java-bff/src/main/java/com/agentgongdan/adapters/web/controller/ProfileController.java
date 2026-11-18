package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.UpdateUserProfileRequest;
import com.agentgongdan.adapters.web.dto.UserProfileView;
import com.agentgongdan.app.service.UserProfileService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/profile")
public class ProfileController {

    private final UserProfileService profileService;

    public ProfileController(UserProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping
    public ApiResponse<UserProfileView> get() {
        StpUtil.checkLogin();
        return ApiResponse.ok(profileService.get(StpUtil.getLoginIdAsLong()));
    }

    @PutMapping
    public ApiResponse<UserProfileView> update(@Valid @RequestBody UpdateUserProfileRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:create");
        return ApiResponse.ok(profileService.update(StpUtil.getLoginIdAsLong(), request));
    }
}
