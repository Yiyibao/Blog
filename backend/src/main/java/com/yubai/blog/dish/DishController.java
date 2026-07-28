package com.yubai.blog.dish;

import java.time.Duration;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.common.TooManyRequestsException;
import com.yubai.blog.stats.ViewDailyService;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/dishes")
@Validated
public class DishController {
    /** P0-2：公开写接口按 IP+slug 限流，防脚本无限刷计数。 */
    static final int FAVORITE_LIMIT = 10;
    static final Duration FAVORITE_WINDOW = Duration.ofMinutes(1);

    /** 3C：P1-8 浏览量去重窗口——同 IP 同菜谱 10 分钟内只计一次。 */
    static final Duration VIEW_DEDUP_WINDOW = Duration.ofMinutes(10);

    private final DishService service;
    private final RateLimiter rateLimiter;
    private final ViewDailyService viewDaily;

    public DishController(DishService service, RateLimiter rateLimiter, ViewDailyService viewDaily) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.viewDaily = viewDaily;
    }

    @GetMapping
    public ApiResponse<PageResponse<DishResponse>> findAll(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size,
        @RequestParam(required = false) String categorySlug,
        @RequestParam(required = false) String query
    ) {
        return ApiResponse.ok(service.findPublished(page, size, categorySlug, query));
    }

    @GetMapping("/{slug}")
    public ApiResponse<DishResponse> findBySlug(@PathVariable String slug, HttpServletRequest request) {
        // 3C：详情读即计浏览量（P1-8 模式）——去重窗口内重复访问与未发布 slug 都不计数
        var clientIp = ClientIps.resolve(request);
        if (rateLimiter.tryAcquire("view:dish:" + clientIp + ":" + slug, 1, VIEW_DEDUP_WINDOW)) {
            service.registerView(slug);
            viewDaily.bump(); // 4D：全站日趋势同窗累加
        }
        return ApiResponse.ok(service.findPublishedBySlug(slug));
    }

    /** P0-7（已批准）：纯计数收藏，接口路径不变。 */
    @PostMapping("/{slug}/favorite")
    public ApiResponse<DishFavoriteResponse> favorite(@PathVariable String slug, HttpServletRequest request) {
        var clientIp = ClientIps.resolve(request);
        if (!rateLimiter.tryAcquire("favorite:" + clientIp + ":" + slug, FAVORITE_LIMIT, FAVORITE_WINDOW)) {
            throw new TooManyRequestsException("操作过于频繁，请稍后再试");
        }
        return ApiResponse.ok(service.favorite(slug));
    }

    @GetMapping("/favorites")
    public ApiResponse<PageResponse<DishFavoriteItem>> findFavorites(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ApiResponse.ok(service.findFavorites(page, size));
    }
}
