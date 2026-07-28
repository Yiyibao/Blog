package com.yubai.blog.post;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PostCategoryRequest(
    @NotBlank @Size(max = 80) String name,
    @Size(max = 500) String description
) {}
