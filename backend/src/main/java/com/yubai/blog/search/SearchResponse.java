package com.yubai.blog.search;

import java.util.List;

public record SearchResponse(
    List<SearchResult> articles,
    List<SearchResult> notes,
    List<SearchResult> dishes,
    int total
) {
    public static SearchResponse empty() {
        return new SearchResponse(List.of(), List.of(), List.of(), 0);
    }
}
