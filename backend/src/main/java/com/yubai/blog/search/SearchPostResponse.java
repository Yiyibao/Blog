package com.yubai.blog.search;

import java.util.List;

public record SearchPostResponse(
    String type,
    String query,
    List<SearchResult> results,
    int page,
    int size,
    long totalElements,
    int totalPages
) {
    public static SearchPostResponse empty(String type, String query) {
        return new SearchPostResponse(type, query, List.of(), 0, 0, 0, 0);
    }
}
