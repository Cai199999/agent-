package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.ProfileReviewView;
import com.agentgongdan.adapters.web.dto.ReviewProfileRequest;
import com.agentgongdan.adapters.web.dto.UpdateUserProfileRequest;
import com.agentgongdan.adapters.web.dto.UserProfileView;
import com.agentgongdan.app.service.UserProfileService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
        boolean admin = StpUtil.getRoleList().contains("ADMIN");
        return ApiResponse.ok(profileService.update(StpUtil.getLoginIdAsLong(), request, admin));
    }

    @GetMapping("/reviews")
    public ApiResponse<List<ProfileReviewView>> pendingReviews() {
        StpUtil.checkLogin();
        StpUtil.checkPermission("admin:user");
        return ApiResponse.ok(profileService.pendingReviews());
    }

    @PostMapping("/reviews/{userId}")
    public ApiResponse<Void> review(@PathVariable long userId,
                                    @RequestBody ReviewProfileRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("admin:user");
        profileService.review(userId, request, StpUtil.getLoginIdAsLong());
        return ApiResponse.ok(null);
    }
}
