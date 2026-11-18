package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.MasterDataOptionsView;
import com.agentgongdan.app.service.MasterDataService;
import com.agentgongdan.common.api.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/master-data")
public class MasterDataController {

    private final MasterDataService masterDataService;

    public MasterDataController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @GetMapping("/options")
    public ApiResponse<MasterDataOptionsView> options() {
        StpUtil.checkLogin();
        StpUtil.checkPermission("ticket:read");
        return ApiResponse.ok(masterDataService.options());
    }
}
