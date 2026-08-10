package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiGeneratedImageResponse;
import com.yubai.blog.admin.ai.AiImageGenerateRequest;
import com.yubai.blog.admin.ai.AiImageGenerateResponse;
import com.yubai.blog.admin.ai.AiImageModelResponse;
import com.yubai.blog.admin.ai.AiImageService;
import com.yubai.blog.admin.ai.AiImageSessionResponse;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;
import com.yubai.blog.config.AiImageProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/ai/images")
public class AdminAiImageController {
    private final AiImageService service;
    private final AiImageProperties properties;
    private final RateLimiter rateLimiter;

    public AdminAiImageController(
            AiImageService service, AiImageProperties properties, RateLimiter rateLimiter) {
        this.service = service;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/models")
    public ApiResponse<List<AiImageModelResponse>> models() {
        return ApiResponse.ok(service.listModels());
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AiImageSessionResponse>> sessions() {
        return ApiResponse.ok(service.listSessions(currentOwner()));
    }

    @GetMapping("/sessions/{sessionId}/images")
    public ApiResponse<List<AiGeneratedImageResponse>> sessionImages(@PathVariable Long sessionId) {
        return ApiResponse.ok(service.sessionImages(sessionId, currentOwner()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<Void> deleteSession(@PathVariable Long sessionId) {
        service.deleteSession(sessionId, currentOwner());
        return ApiResponse.ok(null);
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<AiImageGenerateResponse> generate(
            @Valid @RequestBody AiImageGenerateRequest request, HttpServletRequest httpRequest) {
        return generate(request, null, httpRequest);
    }

    /** Reference-image requests use a JSON payload part plus the binary source image. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiImageGenerateResponse> generateWithReference(
            @Valid @RequestPart("payload") AiImageGenerateRequest request,
            @RequestPart("referenceImage") MultipartFile referenceImage,
            HttpServletRequest httpRequest) {
        return generate(request, referenceImage, httpRequest);
    }

    private ApiResponse<AiImageGenerateResponse> generate(
            AiImageGenerateRequest request,
            MultipartFile referenceImage,
            HttpServletRequest httpRequest) {
        var key = "ai-image:" + httpRequest.getRemoteAddr();
        if (!rateLimiter.tryAcquire(
                key,
                Math.max(1, properties.getRateLimit()),
                Duration.ofSeconds(Math.max(1, properties.getRateWindowSeconds())))) {
            throw new TooManyRequestsException("图片生成请求过于频繁，请稍后再试");
        }
        var result =
                referenceImage == null
                        ? service.generate(request, currentOwner())
                        : service.generate(request, currentOwner(), referenceImage);
        return ApiResponse.ok(result);
    }

    @GetMapping("/{publicId}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID publicId) {
        var owner = currentOwner();
        var entity = service.find(publicId, owner);
        var data = service.read(publicId, owner);
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
        service.delete(publicId, currentOwner());
        return ApiResponse.ok(null);
    }

    /** 该路由整体受 JWT 保护，未认证上下文的兜底仅用于测试场景。 */
    private static String currentOwner() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken jwtAuth) return jwtAuth.getName();
        return "admin";
    }
}
