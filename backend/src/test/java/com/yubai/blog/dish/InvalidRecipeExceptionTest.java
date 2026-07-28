package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvalidRecipeExceptionTest {
    @Test
    void exceptionCarriesMessage() {
        var ex = new InvalidRecipeException("test error");
        assertThat(ex.getMessage()).isEqualTo("test error");
    }
}
