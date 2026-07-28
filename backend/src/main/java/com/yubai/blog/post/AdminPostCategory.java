package com.yubai.blog.post;

public record AdminPostCategory(
    long id,
    String name,
    String slug,
    String description,
    long postCount,
    long publishedPostCount
) {}
