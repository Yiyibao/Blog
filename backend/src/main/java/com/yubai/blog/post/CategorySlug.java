package com.yubai.blog.post;

import java.util.Locale;

public final class CategorySlug {
    private CategorySlug() {
    }

    public static String fromName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
