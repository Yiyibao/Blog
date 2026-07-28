package com.yubai.blog.dish;

public record AdminDishCategory(
    long id, String name, String slug, String description, long dishCount, long publishedDishCount
) {}
