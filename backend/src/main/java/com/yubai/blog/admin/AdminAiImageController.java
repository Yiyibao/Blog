package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiGeneratedImageResponse;
import com.yubai.blog.admin.ai.AiGeneratedImageEntity;
import com.yubai.blog.admin.ai.AiImageGenerateRequest;
import com.yubai.blog.admin.ai.AiImageModelResponse;
import com.yubai.blog.admin.ai.AiImageService;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.yubai.blog.config.AiImageProperties;

@RestController
@RequestMapping("/api/v1/admin/ai/images")
public class AdminAiImageController {
    private final AiImageService service;
    private final AiImageProperties properties;
    private final RateLimiter rateLimiter;

    public AdminAiImageController(AiImageService service, AiImageProperties properties, RateLimiter rateLimiter) {
        this.service = service;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/models")
    public ApiResponse<List<AiImageModelResponse>> models() {
        return ApiResponse.ok(service.listModels());
    }

    @PostMapping
    public ApiResponse<List<AiGeneratedImageResponse>> generate(@Valid @RequestBody AiImageGenerateRequest request,
                                                                 HttpServletRequest httpRequest) {
        var key = "ai-image:" + httpRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(key, Math.max(1, properties.getRateLimit()),
            Duration.ofSeconds(Math.max(1, properties.getRateWindowSeconds())))) {
            throw new TooManyRequestsException("图片生成请求过于频繁，请稍后再试");
        }
        return ApiResponse.ok(service.generate(request));
    }

    @GetMapping("/{publicId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID publicId) {
        var entity = service.find(publicId);
        var data = service.read(publicId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(entity.getMediaType()))
            .contentLength(entity.getByteSize())
            .eTag("\"" + entity.getSha256() + "\"")
            .header("Cache-Control", "private, max-age=3600")
            .header("X-Content-Type-Options", "nosniff")
            .body(data);
    }

    @DeleteMapping("/{publicId}")
    public ApiResponse<Void> delete(@PathVariable UUID publicId) {
        service.delete(publicId);
        return ApiResponse.ok(null);
    }
}
