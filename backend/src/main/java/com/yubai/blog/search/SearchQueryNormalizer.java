package com.yubai.blog.search;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Deterministic, bounded query normalization for the PostgreSQL search path. */
public final class SearchQueryNormalizer {
    private static final Map<String, String> ALIASES = aliases();

    private SearchQueryNormalizer() {}

    public static String normalize(String query) {
        if (query == null) return "";
        var value = query.trim().toLowerCase(Locale.ROOT);
        if (value.isBlank()) return "";
        for (var entry : ALIASES.entrySet()) {
            value = value.replace(entry.getKey(), entry.getValue());
        }
        return value.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> aliases() {
        var aliases = new LinkedHashMap<String, String>();
        aliases.put("教程", "指南");
        aliases.put("教学", "指南");
        aliases.put("食谱", "菜谱");
        aliases.put("料理", "菜谱");
        aliases.put("博文", "文章");
        aliases.put("javasript", "javascript");
        aliases.put("javascirpt", "javascript");
        aliases.put("postgre", "postgresql");
        return Collections.unmodifiableMap(aliases);
    }
}
