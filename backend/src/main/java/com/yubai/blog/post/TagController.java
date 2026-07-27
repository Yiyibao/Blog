package com.yubai.blog.post;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 5B：标签一等公民——聚合列表与按标签文章分页（仅已发布）。 */
@RestController
@RequestMapping("/api/v1/tags")
@Validated
public class TagController {

    private final PostService service;

    public TagController(PostService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<PostService.TagSummary>> list() {
        return ApiResponse.ok(service.findPublishedTags());
    }

    @GetMapping("/{tag}")
    public ApiResponse<PageResponse<PostSummary>> byTag(
        @PathVariable String tag,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "10") @Min(1) @Max(50) int size
    ) {
        return ApiResponse.ok(service.findPublishedByTag(tag, page, size));
    }
}
