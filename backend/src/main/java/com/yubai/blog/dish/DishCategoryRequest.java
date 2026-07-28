package com.yubai.blog.dish;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DishCategoryRequest(
    @NotBlank @Size(max = 60) String name,
    @Size(max = 500) String description
) {}
