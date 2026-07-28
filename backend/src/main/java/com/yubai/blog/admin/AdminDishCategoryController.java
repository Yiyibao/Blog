package com.yubai.blog.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.dish.AdminDishCategory;
import com.yubai.blog.dish.DishCategoryRequest;
import com.yubai.blog.dish.DishCategoryService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/dish-categories")
public class AdminDishCategoryController {
    private final DishCategoryService service;
    public AdminDishCategoryController(DishCategoryService service) { this.service = service; }

    @GetMapping public ApiResponse<List<AdminDishCategory>> findAll() { return ApiResponse.ok(service.findAll()); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminDishCategory> create(@Valid @RequestBody DishCategoryRequest request) {
        return ApiResponse.created(service.create(request));
    }
    @PutMapping("/{id}")
    public ApiResponse<AdminDishCategory> update(@PathVariable long id, @Valid @RequestBody DishCategoryRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id); }
}
