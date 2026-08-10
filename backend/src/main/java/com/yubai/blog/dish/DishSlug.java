package com.yubai.blog.dish;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;

final class DishSlug {
    private static final int MAX_LENGTH = 120;

    private DishSlug() {}

    static String fromName(String name) {
        var normalized =
                Normalizer.normalize(name, Normalizer.Form.NFKD)
                        .replaceAll("\\p{M}+", "")
                        .toLowerCase(Locale.ROOT);
        var readable =
                normalized
                        .replaceAll("[^a-z0-9]+", "-")
                        .replaceAll("^-+|-+$", "")
                        .replaceAll("-+", "-");
        if (!readable.isBlank()) {
            return readable.substring(0, Math.min(readable.length(), MAX_LENGTH));
        }
        return "dish-" + shortHash(name);
    }

    static String withSuffix(String base, int number) {
        var suffix = "-" + number;
        var prefixLength = Math.min(base.length(), MAX_LENGTH - suffix.length());
        return base.substring(0, prefixLength) + suffix;
    }

    private static String shortHash(String value) {
        try {
            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
