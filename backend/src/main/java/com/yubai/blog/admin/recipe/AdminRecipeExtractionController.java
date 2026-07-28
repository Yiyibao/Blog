package com.yubai.blog.admin.recipe;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/recipe-extractions")
@Validated
public class AdminRecipeExtractionController {
    private final RecipeExtractionService extractionService;

    public AdminRecipeExtractionController(RecipeExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RecipeExtractionResponse> create(@Valid @RequestBody RecipeExtractionRequest request) {
        return ApiResponse.created(extractionService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<RecipeExtractionResponse> getJob(@PathVariable long id) {
        return ApiResponse.ok(extractionService.getJob(id));
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable long id) {
        extractionService.cancelJob(id);
    }
}
