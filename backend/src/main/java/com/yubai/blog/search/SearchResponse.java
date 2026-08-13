package com.yubai.blog.search;

import java.util.List;
import java.util.UUID;

public record SearchResponse(
        List<SearchResult> articles,
        List<SearchResult> notes,
        List<SearchResult> dishes,
        int total,
        UUID telemetryId) {
    public SearchResponse(
            List<SearchResult> articles,
            List<SearchResult> notes,
            List<SearchResult> dishes,
            int total) {
        this(articles, notes, dishes, total, null);
    }

    public static SearchResponse empty() {
        return new SearchResponse(List.of(), List.of(), List.of(), 0, null);
    }
}
