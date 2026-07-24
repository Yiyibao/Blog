package com.yubai.blog.search;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SearchRequest(
    @NotBlank String query,
    @NotNull SearchType type,
    @Min(0) int page,
    @Min(1) int size
) {
    public SearchRequest {
        if (type == null) type = SearchType.ALL;
        if (page < 0) page = 0;
        if (size < 1) size = 10;
    }
}
