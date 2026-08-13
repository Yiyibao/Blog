package com.yubai.blog.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;

/** Search contract shared by the public UI, archive and AI read-only tool. */
public record SearchRequest(
        @NotBlank String query,
        @NotNull SearchType type,
        @Min(0) int page,
        @Min(1) @Max(50) int size,
        String categorySlug,
        SearchSort sort,
        String tag,
        LocalDate from,
        LocalDate to) {
    public SearchRequest(
            String query,
            SearchType type,
            int page,
            int size,
            String categorySlug,
            SearchSort sort) {
        this(query, type, page, size, categorySlug, sort, null, null, null);
    }

    public SearchSort sortOrDefault() {
        return sort == null ? SearchSort.RELEVANCE : sort;
    }

    public String categorySlugOrNull() {
        return categorySlug == null || categorySlug.isBlank() ? null : categorySlug;
    }

    public String tagOrNull() {
        return tag == null || tag.isBlank() ? null : tag.trim();
    }

    public boolean hasPostFilters() {
        return categorySlugOrNull() != null || tagOrNull() != null || from != null || to != null;
    }

    public boolean hasDateFilters() {
        return from != null || to != null;
    }

    public boolean hasInvalidDateRange() {
        return from != null && to != null && from.isAfter(to);
    }
}
