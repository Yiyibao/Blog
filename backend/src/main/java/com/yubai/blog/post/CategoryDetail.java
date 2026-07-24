package com.yubai.blog.post;

import java.util.List;

import com.yubai.blog.common.PageResponse;

public record CategoryDetail(
    String name,
    String slug,
    String description,
    long total,
    List<PostSummary> posts,
    int page,
    int size,
    int totalPages
) {
    static CategoryDetail from(String name, String slug, String description, PageResponse<PostSummary> page) {
        return new CategoryDetail(
            name, slug, description, page.totalElements(),
            page.items(), page.page(), page.size(), page.totalPages()
        );
    }
}
