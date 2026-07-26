package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiProviderRequest;
import com.yubai.blog.admin.ai.AiProviderResponse;
import com.yubai.blog.admin.ai.AiProviderService;
import com.yubai.blog.admin.ai.AiProviderTestResult;
import com.yubai.blog.common.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 4A-1：AI 供应商管理接口（/api/v1/admin/** 已由 Security 白名单外的 ADMIN 规则保护）。
 * 密钥只写不回显；连通测试由后端代发，避免密钥经过浏览器。
 */
@RestController
@RequestMapping("/api/v1/admin/ai/providers")
public class AdminAiProviderController {
    private final AiProviderService providerService;

    public AdminAiProviderController(AiProviderService providerService) {
        this.providerService = providerService;
    }

    @GetMapping
    public ApiResponse<List<AiProviderResponse>> list() {
        return ApiResponse.ok(providerService.list());
    }

    @PostMapping
    public ApiResponse<AiProviderResponse> create(@Valid @RequestBody AiProviderRequest request) {
        return ApiResponse.ok(providerService.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<AiProviderResponse> update(@PathVariable Long id,
                                                  @Valid @RequestBody AiProviderRequest request) {
        return ApiResponse.ok(providerService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        providerService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/default")
    public ApiResponse<AiProviderResponse> setDefault(@PathVariable Long id) {
        return ApiResponse.ok(providerService.setDefault(id));
    }

    @PostMapping("/{id}/test")
    public ApiResponse<AiProviderTestResult> testConnection(@PathVariable Long id) {
        return ApiResponse.ok(providerService.testConnection(id));
    }
}
