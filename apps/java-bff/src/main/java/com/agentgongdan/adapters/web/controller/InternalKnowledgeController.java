package com.agentgongdan.adapters.web.controller;

import com.agentgongdan.adapters.web.dto.KnowledgeHitView;
import com.agentgongdan.adapters.web.dto.KnowledgeSearchRequest;
import com.agentgongdan.app.service.KnowledgeService;
import com.agentgongdan.common.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/internal/v1/knowledge")
public class InternalKnowledgeController {

    private final KnowledgeService knowledgeService;

    public InternalKnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @PostMapping("/search")
    public ApiResponse<List<KnowledgeHitView>> search(
            @Valid @RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(knowledgeService.search(request));
    }
}
