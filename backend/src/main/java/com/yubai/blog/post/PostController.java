package com.yubai.blog.post;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1")
public class PostController {
    private final PostService service;

    public PostController(PostService service) {
        this.service = service;
    }

    @GetMapping("/posts")
    public ApiResponse<List<PostResponse>> findAll() {
        return ApiResponse.ok(service.findAll());
    }

    @GetMapping("/posts/{slug}")
    public ApiResponse<PostResponse> findBySlug(@PathVariable String slug) {
        return ApiResponse.ok(service.findBySlug(slug));
    }

    @GetMapping("/categories")
    public ApiResponse<List<String>> findCategories() {
        return ApiResponse.ok(service.findCategories());
    }
}
