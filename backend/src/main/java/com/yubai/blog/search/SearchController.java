package com.yubai.blog.search;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/search")
@Validated
public class SearchController {

    private final SearchService searchService;
    private final SearchTelemetryService telemetryService;

    public SearchController(SearchService searchService, SearchTelemetryService telemetryService) {
        this.searchService = searchService;
        this.telemetryService = telemetryService;
    }

    /** L-16/D-17：游客搜索剔除学习笔记（分组分支笔记恒空；类型化分支 NOTE 返回空页）。 */
    @GetMapping
    public ApiResponse<SearchResponse> search(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "5") @Min(1) @Max(10) int limit) {
        var started = System.nanoTime();
        var result = searchService.search(q, limit, CurrentUser.isAuthenticated());
        var telemetryId =
                telemetryService.record(
                        q,
                        CurrentUser.isAuthenticated() ? "PRIVATE" : "PUBLIC",
                        result.total(),
                        System.nanoTime() - started);
        return ApiResponse.ok(
                new SearchResponse(
                        result.articles(),
                        result.notes(),
                        result.dishes(),
                        result.total(),
                        telemetryId));
    }

    @PostMapping
    public ApiResponse<SearchPostResponse> searchByType(@Valid @RequestBody SearchRequest request) {
        var started = System.nanoTime();
        var result = searchService.search(request, CurrentUser.isAuthenticated());
        var telemetryId =
                telemetryService.record(
                        request.query(),
                        CurrentUser.isAuthenticated() ? "PRIVATE" : "PUBLIC",
                        Math.toIntExact(Math.min(Integer.MAX_VALUE, result.totalElements())),
                        System.nanoTime() - started);
        return ApiResponse.ok(
                new SearchPostResponse(
                        result.type(),
                        result.query(),
                        result.results(),
                        result.page(),
                        result.size(),
                        result.totalElements(),
                        result.totalPages(),
                        telemetryId));
    }

    @PostMapping("/events/{eventId}/click")
    public ApiResponse<Void> recordClick(
            @PathVariable UUID eventId, @Valid @RequestBody ClickRequest request) {
        telemetryService.recordClick(eventId, request.position());
        return ApiResponse.ok(null);
    }

    public record ClickRequest(@Min(1) @Max(100) int position) {}
}
