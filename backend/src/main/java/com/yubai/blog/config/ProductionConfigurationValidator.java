package com.yubai.blog.config;

import java.net.URI;
import java.util.ArrayList;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails a production-profile startup before it can serve with development defaults. */
@Component
@Profile("prod")
public class ProductionConfigurationValidator implements ApplicationRunner {
    private final Environment environment;

    public ProductionConfigurationValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        var errors = new ArrayList<String>();
        require("spring.datasource.url", errors);
        require("spring.datasource.username", errors);
        require("spring.datasource.password", errors);
        require("app.jwt.secret", errors);
        var siteUrl = require("app.site-url", errors);
        var origins = require("app.cors.allowed-origins", errors);

        if (siteUrl != null && !isHttps(siteUrl)) errors.add("app.site-url must use https");
        if (origins != null
                && java.util.Arrays.stream(origins.split(","))
                        .anyMatch(value -> !isHttps(value.trim()))) {
            errors.add("app.cors.allowed-origins must contain only https origins");
        }
        if (!environment.getProperty("app.jwt.cookie-secure", Boolean.class, false)) {
            errors.add("app.jwt.cookie-secure must be true");
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Production configuration is invalid: " + String.join("; ", errors));
        }
    }

    private String require(String key, java.util.List<String> errors) {
        var value = environment.getProperty(key);
        if (value == null || value.isBlank()) {
            errors.add(key + " is required");
            return null;
        }
        return value;
    }

    private static boolean isHttps(String value) {
        try {
            return "https".equalsIgnoreCase(URI.create(value).getScheme());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
