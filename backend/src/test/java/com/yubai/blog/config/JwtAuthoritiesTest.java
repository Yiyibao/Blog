package com.yubai.blog.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import com.yubai.blog.auth.Permissions;

class JwtAuthoritiesTest {
    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void usesKnownAuthoritiesFromNewTokens() {
        var authentication = configuration.jwtAuthenticationConverter().convert(jwt(
            "authorities", List.of(Permissions.AI_USAGE, "unknown:permission")));

        assertThat(authentication.getAuthorities())
            .extracting(Object::toString)
            .containsExactly(Permissions.AI_USAGE);
    }

    @Test
    void mapsAllRolesForRollingDeployCompatibility() {
        var authentication = configuration.jwtAuthenticationConverter().convert(jwt(
            "roles", List.of("UNKNOWN", "PARTNER")));

        assertThat(authentication.getAuthorities())
            .extracting(Object::toString)
            .containsExactlyInAnyOrder(Permissions.ACCOUNT_ACCESS, Permissions.KITCHEN_ACCESS);
    }

    @Test
    void unknownClaimsFailClosed() {
        var authentication = configuration.jwtAuthenticationConverter().convert(jwt(
            "authorities", List.of("unknown:permission")));

        assertThat(authentication.getAuthorities()).isEmpty();
    }

    private static Jwt jwt(String claim, List<String> values) {
        return Jwt.withTokenValue("test")
            .header("alg", "none")
            .subject("user")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim(claim, values)
            .build();
    }
}
