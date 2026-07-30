package com.yubai.blog.dish;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DishImportCommitRequest(
    @NotBlank @Size(max = 60) String category,
    @Size(max = 120) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String correctedSlug,
    boolean published
) {
    public DishImportCommitRequest(String category, String correctedSlug) {
        this(category, correctedSlug, false);
    }
}
