package com.yubai.blog.post;

public record CategorySummary(
    String name,
    String slug,
    String description,
    long publishedPostCount
) {
    public CategorySummary(String name, String slug, long publishedPostCount) {
        this(name, slug, null, publishedPostCount);
    }
}
