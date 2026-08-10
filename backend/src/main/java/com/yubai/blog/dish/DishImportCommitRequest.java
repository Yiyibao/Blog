package com.yubai.blog.dish;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DishImportCommitRequest(
        @NotBlank @Size(max = 60) String category, boolean published) {
    public DishImportCommitRequest(String category) {
        this(category, false);
    }
}
