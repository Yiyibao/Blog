package com.yubai.blog.post;

/**
 * Test helper to compute expected category slugs.
 */
public final class PostCategorySlugHelper {

    private PostCategorySlugHelper() {
    }

    public static String slugFor(String categoryName) {
        return CategorySlug.fromName(categoryName);
    }
}
