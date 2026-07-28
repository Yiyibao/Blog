package com.yubai.blog.admin.recipe;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RecipeExtractionRequest(
    @NotBlank @Pattern(regexp = "TEXT|WEB_URL") String sourceType,
    @NotBlank @Size(max = 100000) String sourceContent,
    Long providerId,
    @Size(max = 120) String model
) {}
