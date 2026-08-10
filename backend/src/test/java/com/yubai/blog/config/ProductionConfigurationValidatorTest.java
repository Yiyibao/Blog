package com.yubai.blog.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class ProductionConfigurationValidatorTest {
    private static final DefaultApplicationArguments NO_ARGS = new DefaultApplicationArguments();

    @Test
    void rejectsDevelopmentDefaultsAndMissingSecrets() {
        var environment =
                new MockEnvironment()
                        .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/blog")
                        .withProperty("spring.datasource.username", "app")
                        .withProperty("app.site-url", "http://localhost:5173")
                        .withProperty("app.cors.allowed-origins", "http://localhost:5173")
                        .withProperty("app.jwt.cookie-secure", "false");

        assertThrows(
                IllegalStateException.class,
                () -> new ProductionConfigurationValidator(environment).run(NO_ARGS));
    }

    @Test
    void acceptsCompleteHttpsProductionConfiguration() {
        var environment =
                new MockEnvironment()
                        .withProperty("spring.datasource.url", "jdbc:postgresql://db/blog")
                        .withProperty("spring.datasource.username", "app")
                        .withProperty("spring.datasource.password", "secret")
                        .withProperty(
                                "app.jwt.secret",
                                "a-secret-that-is-longer-than-thirty-two-characters")
                        .withProperty("app.site-url", "https://example.com")
                        .withProperty("app.cors.allowed-origins", "https://example.com")
                        .withProperty("app.jwt.cookie-secure", "true");

        assertDoesNotThrow(() -> new ProductionConfigurationValidator(environment).run(NO_ARGS));
    }
}
