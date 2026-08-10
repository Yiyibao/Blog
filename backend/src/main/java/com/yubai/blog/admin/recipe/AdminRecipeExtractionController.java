package com.yubai.blog.admin.recipe;

import com.yubai.blog.common.ApiResponse;
import jakarta.validation.Valid;
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

@RestController
@RequestMapping("/api/v1/admin/recipe-extractions")
@Validated
public class AdminRecipeExtractionController {
    private final RecipeExtractionService extractionService;

    public AdminRecipeExtractionController(RecipeExtractionService extractionService) {
        this.extractionService = extractionService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<RecipeExtractionResponse>> create(
            @Valid @RequestBody RecipeExtractionRequest request) {
        return ResponseEntity.accepted().body(ApiResponse.ok(extractionService.create(request)));
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

    @PostMapping("/{id}/retry")
    public ResponseEntity<ApiResponse<RecipeExtractionResponse>> retry(@PathVariable long id) {
        return ResponseEntity.accepted().body(ApiResponse.ok(extractionService.retryJob(id)));
    }
}
