package com.yubai.blog.search;

public record SearchResult(
    String type,
    long id,
    String title,
    String excerpt,
    String category,
    String url,
    String color,
    String number,
    String slug
) {
}
