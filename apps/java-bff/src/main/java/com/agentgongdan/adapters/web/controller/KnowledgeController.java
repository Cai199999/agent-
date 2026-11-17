package com.agentgongdan.adapters.web.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.agentgongdan.adapters.web.dto.KnowledgeDocView;
import com.agentgongdan.adapters.web.dto.KnowledgeDownloadView;
import com.agentgongdan.adapters.web.dto.KnowledgeHitView;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.adapters.web.dto.PageResult;
import com.agentgongdan.app.service.KnowledgeService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping(value = "/docs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeDocView> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam("title") String title,
            @RequestParam(value = "categoryCode", defaultValue = "GENERAL") String categoryCode) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("knowledge:manage");
        return ApiResponse.ok(knowledgeService.upload(
                file, title, categoryCode, StpUtil.getLoginIdAsLong()));
    }

    @GetMapping("/docs")
    public ApiResponse<PageResult<KnowledgeDocView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long pageSize) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("knowledge:read");
        return ApiResponse.ok(knowledgeService.list(page, Math.min(pageSize, 100)));
    }

    @PostMapping("/search")
    public ApiResponse<List<KnowledgeHitView>> search(
            @Valid @RequestBody KnowledgeSearchRequest request) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("knowledge:read");
        return ApiResponse.ok(knowledgeService.search(request));
    }

    @GetMapping("/docs/{id}/download")
    public ApiResponse<KnowledgeDownloadView> download(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("knowledge:read");
        return ApiResponse.ok(knowledgeService.download(id));
    }

    @DeleteMapping("/docs/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        StpUtil.checkLogin();
        StpUtil.checkPermission("knowledge:manage");
        knowledgeService.delete(id, StpUtil.getLoginIdAsLong());
        return ApiResponse.ok(null);
    }
}
