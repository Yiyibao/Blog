package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiProviderRequest;
import com.yubai.blog.admin.ai.AiProviderResponse;
import com.yubai.blog.admin.ai.AiProviderService;
import com.yubai.blog.admin.ai.AiProviderTestResult;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
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
    /** 连通测试代发外部请求，单独限流防止被当作出网探测跳板刷请求。 */
    static final int TEST_LIMIT = 6;
    static final Duration TEST_WINDOW = Duration.ofMinutes(1);

    private final AiProviderService providerService;
    private final RateLimiter rateLimiter;

    public AdminAiProviderController(AiProviderService providerService, RateLimiter rateLimiter) {
        this.providerService = providerService;
        this.rateLimiter = rateLimiter;
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
    public ApiResponse<AiProviderTestResult> testConnection(@PathVariable Long id, HttpServletRequest request) {
        var clientIp = ClientIps.resolve(request);
        if (!rateLimiter.tryAcquire("ai-provider-test:" + clientIp, TEST_LIMIT, TEST_WINDOW)) {
            throw new TooManyRequestsException("连通测试过于频繁，请稍后再试");
        }
        return ApiResponse.ok(providerService.testConnection(id));
    }
}
