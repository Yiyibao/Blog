package com.yubai.blog.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.dish.DishRequest;
import com.yubai.blog.dish.DishResponse;
import com.yubai.blog.dish.DishService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/dishes")
@Validated
public class AdminDishController {
    private final DishService service;

    public AdminDishController(DishService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<DishResponse>> findAll(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) {
        return ApiResponse.ok(service.findAll(page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<DishResponse> findOne(@PathVariable long id) {
        return ApiResponse.ok(service.findOne(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DishResponse> create(@Valid @RequestBody DishRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<DishResponse> update(@PathVariable long id, @Valid @RequestBody DishRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
