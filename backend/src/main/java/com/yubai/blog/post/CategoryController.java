package com.yubai.blog.post;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {

    private final PostService postService;

    public CategoryController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping
    public ApiResponse<List<CategorySummary>> findCategories() {
        return ApiResponse.ok(postService.findCategorySummaries());
    }

    @GetMapping("/{slug}")
    public ApiResponse<CategoryDetail> findBySlug(
        @PathVariable String slug,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size
    ) {
        return ApiResponse.ok(postService.findCategoryBySlug(slug, page, size));
    }
}
