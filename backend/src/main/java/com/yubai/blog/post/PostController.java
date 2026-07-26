package com.yubai.blog.post;

import java.time.Duration;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;
import com.yubai.blog.series.SeriesService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping({"/api/v1"})
@Validated // P2-2：分页参数声明式校验，非法值统一 400
public class PostController {
    /** P0-2：公开写接口按 IP+slug 限流，防脚本无限刷计数。 */
    static final int LIKE_LIMIT = 10;
    static final Duration LIKE_WINDOW = Duration.ofMinutes(1);

    /** P1-8：浏览量去重窗口——同 IP 同文章 10 分钟内只计一次（复用限流器基建，IP 仅存于进程内窗口，不落库）。 */
    static final Duration VIEW_DEDUP_WINDOW = Duration.ofMinutes(10);

    private final PostService service;
    private final RateLimiter rateLimiter;
    private final SeriesService seriesService;

    public PostController(PostService service, RateLimiter rateLimiter, SeriesService seriesService) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.seriesService = seriesService;
    }

    /**
     * P1-2：列表返回摘要（不含正文）；categorySlug 过滤分类，sort=asc 最早优先（缺省最新优先）。
     * L-9：featured=true 只出精选文章（忽略 categorySlug/sort，按日期倒序）。
     */
    @GetMapping({"/posts"})
    public ApiResponse<PageResponse<PostSummary>> findPublished(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size,
        @RequestParam(required = false) String categorySlug,
        @RequestParam(defaultValue = "desc") String sort,
        @RequestParam(defaultValue = "false") boolean featured
    ) {
        return ApiResponse.ok(service.findPublished(page, size, categorySlug, sort, featured));
    }

    @GetMapping({"/posts/{slug}"})
    public ApiResponse<PostResponse> findBySlug(@PathVariable String slug, HttpServletRequest request) {
        // P1-8：详情读即计浏览量；先原子 +1 再取详情，响应携带最新计数。
        // 去重窗口内的重复访问与不存在/未发布的 slug 都不会计数（UPDATE 命中 0 行即静默）。
        var clientIp = ClientIps.resolve(request);
        if (rateLimiter.tryAcquire("view:" + clientIp + ":" + slug, 1, VIEW_DEDUP_WINDOW)) {
            service.registerView(slug);
        }
        var response = service.findPublishedBySlug(slug);
        // 4B：补挂「本文属于合集 X（n/N）」（不属于任何已发布合集则为 null）
        var ref = seriesService.seriesRefForPost(response.id());
        if (ref != null) {
            response = response.withSeries(
                new PostResponse.PostSeriesRef(ref.slug(), ref.name(), ref.position(), ref.total()));
        }
        return ApiResponse.ok(response);
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
