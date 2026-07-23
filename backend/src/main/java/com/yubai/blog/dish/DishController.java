package com.yubai.blog.dish;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;

@RestController
@RequestMapping("/api/v1/dishes")
public class DishController {
    private final DishService service;

    public DishController(DishService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<DishResponse>> findAll(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.findPublished(page, size));
    }

    @GetMapping("/{slug}")
    public ApiResponse<DishResponse> findBySlug(@PathVariable String slug) {
        return ApiResponse.ok(service.findPublishedBySlug(slug));
    }
}
