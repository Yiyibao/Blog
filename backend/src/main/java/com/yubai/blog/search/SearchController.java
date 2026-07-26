package com.yubai.blog.search;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.CurrentUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/search")
@Validated
public class SearchController {

    private final SearchService searchService;

    public SearchController(SearchService searchService) {
        this.searchService = searchService;
    }

    /** L-16/D-17：游客搜索剔除学习笔记（分组分支笔记恒空；类型化分支 NOTE 返回空页）。 */
    @GetMapping
    public ApiResponse<SearchResponse> search(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "5") @Min(1) @Max(10) int limit
    ) {
        return ApiResponse.ok(searchService.search(q, limit, CurrentUser.isAuthenticated()));
    }

    @PostMapping
    public ApiResponse<SearchPostResponse> searchByType(@Valid @RequestBody SearchRequest request) {
        return ApiResponse.ok(searchService.search(request, CurrentUser.isAuthenticated()));
    }
}
