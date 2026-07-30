package com.yubai.blog.admin.recipe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.yubai.blog.dish.InvalidRecipeException;

class RecipeUrlValidatorTest {
    private final RecipeUrlValidator validator = new RecipeUrlValidator();

    @Test
    void rejectsNonHttpsAndLoopbackAddresses() {
        assertThatThrownBy(() -> validator.validatePublicHttps("http://example.com/recipe"))
            .isInstanceOf(InvalidRecipeException.class);
        assertThatThrownBy(() -> validator.validatePublicHttps("https://127.0.0.1/private"))
            .isInstanceOf(InvalidRecipeException.class);
        assertThatThrownBy(() -> validator.validatePublicHttps("https://[::1]/private"))
            .isInstanceOf(InvalidRecipeException.class);
    }

    @Test
    void matchesExactAndSubdomainHostsOnly() {
        assertThat(validator.hostMatches(URI.create("https://www.bilibili.com/video/1"),
            java.util.List.of("bilibili.com"))).isTrue();
        assertThat(validator.hostMatches(URI.create("https://evilbilibili.com/video/1"),
            java.util.List.of("bilibili.com"))).isFalse();
    }
}
