package com.yubai.blog.post;

import java.time.Duration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping({"/api/v1"})
public class PostController {
    /** P0-2：公开写接口按 IP+slug 限流，防脚本无限刷计数。 */
    static final int LIKE_LIMIT = 10;
    static final Duration LIKE_WINDOW = Duration.ofMinutes(1);

    private final PostService service;
    private final RateLimiter rateLimiter;

    public PostController(PostService service, RateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping({"/posts"})
    public ApiResponse<PageResponse<PostResponse>> findPublished(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(service.findPublished(page, size));
    }

    @GetMapping({"/posts/{slug}"})
    public ApiResponse<PostResponse> findBySlug(@PathVariable String slug) {
        return ApiResponse.ok(service.findPublishedBySlug(slug));
    }

    @PostMapping({"/posts/{slug}/like"})
    public ApiResponse<PostLikeResponse> likePost(@PathVariable String slug, HttpServletRequest request) {
        var clientIp = ClientIps.resolve(request);
        if (!rateLimiter.tryAcquire("like:" + clientIp + ":" + slug, LIKE_LIMIT, LIKE_WINDOW)) {
            throw new TooManyRequestsException("操作过于频繁，请稍后再试");
        }
        return ApiResponse.ok(service.likePost(slug));
    }

    @GetMapping({"/posts/{slug}/stats"})
    public ApiResponse<PostStatsResponse> getStats(@PathVariable String slug) {
        return ApiResponse.ok(service.getStats(slug));
    }
}
