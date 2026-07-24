package com.yubai.blog.post;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;

@RestController
@RequestMapping({"/api/v1"})
public class PostController {
    private final PostService service;

    public PostController(PostService service) {
        this.service = service;
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
}