package com.yubai.blog.search;

import java.util.List;
import java.util.UUID;

public record SearchPostResponse(
        String type,
        String query,
        List<SearchResult> results,
        int page,
        int size,
        long totalElements,
        int totalPages,
        UUID telemetryId) {
    public SearchPostResponse(
            String type,
            String query,
            List<SearchResult> results,
            int page,
            int size,
            long totalElements,
            int totalPages) {
        this(type, query, results, page, size, totalElements, totalPages, null);
    }

    public static SearchPostResponse empty(String type, String query) {
        return new SearchPostResponse(type, query, List.of(), 0, 0, 0, 0, null);
    }
}
