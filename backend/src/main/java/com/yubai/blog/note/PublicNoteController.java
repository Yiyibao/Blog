package com.yubai.blog.note;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.ClientIps;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.RateLimiter;
import com.yubai.blog.stats.ViewCounter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notes")
@Validated
public class PublicNoteController {
    /** 3C：P1-8 浏览量去重窗口——同 IP 同笔记 10 分钟内只计一次（IP 仅存于进程内窗口）。 */
    static final Duration VIEW_DEDUP_WINDOW = Duration.ofMinutes(10);

    private final NoteService service;
    private final RateLimiter rateLimiter;
    private final ViewCounter viewCounter;

    public PublicNoteController(
            NoteService service, RateLimiter rateLimiter, ViewCounter viewCounter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
        this.viewCounter = viewCounter;
    }

    /** P1-2：列表只出摘要（不含正文），正文经 /{id} 详情获取。 */
    @GetMapping
    public ApiResponse<PageResponse<NoteSummary>> findPublished(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {
        return ApiResponse.ok(service.findPublished(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<NoteResponse> findPublishedOne(
            @PathVariable long id, HttpServletRequest request) {
        // 3C：详情读即计浏览量——先原子 +1 再取详情，响应携带最新计数；未发布/不存在 UPDATE 命中 0 行即静默
        var clientIp = ClientIps.resolve(request);
        if (rateLimiter.tryAcquire("view:note:" + clientIp + ":" + id, 1, VIEW_DEDUP_WINDOW)) {
            viewCounter.record(() -> service.registerView(id));
        }
        return ApiResponse.ok(service.findPublishedOne(id));
    }
}
