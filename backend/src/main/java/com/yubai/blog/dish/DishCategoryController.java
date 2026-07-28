package com.yubai.blog.dish;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/dish-categories")
public class DishCategoryController {

    private final DishCategoryService service;

    public DishCategoryController(DishCategoryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<DishCategorySummary>> findAll() {
        return ApiResponse.ok(service.findAllPublic());
    }
}
