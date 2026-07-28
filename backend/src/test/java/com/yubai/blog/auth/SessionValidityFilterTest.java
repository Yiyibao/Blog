package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import jakarta.servlet.FilterChain;

class SessionValidityFilterTest {
    private final AdminUserRepository repository = mock(AdminUserRepository.class);
    private final SessionValidityFilter filter = new SessionValidityFilter(repository);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void validatesEveryAuthenticatedApiFamily() {
        assertThat(filter.shouldNotFilter(request("/api/v1/admin/posts"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/kitchen/menus"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/notes"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/note-assets/id"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/password"))).isFalse();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/totp/setup"))).isFalse();
    }

    @Test
    void skipsPublicApisAndAuthenticationEntrypoints() {
        assertThat(filter.shouldNotFilter(request("/api/v1/posts"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/login"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/refresh"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/logout"))).isTrue();
        assertThat(filter.shouldNotFilter(request("/api/v1/auth/totp/verify"))).isTrue();
    }

    @Test
    void rejectsStaleTokenBeforeAccountSecurityEndpoint() throws Exception {
        var user = mock(AdminUserEntity.class);
        when(user.getSessionsValidFrom()).thenReturn(Instant.parse("2026-07-28T00:00:30Z"));
        when(user.isEnabled()).thenReturn(true);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));
        authenticate(Instant.parse("2026-07-28T00:00:00Z"));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request("/api/v1/auth/totp/setup"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录已失效");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void rejectsDisabledUser() throws Exception {
        var user = mock(AdminUserEntity.class);
        when(user.getSessionsValidFrom()).thenReturn(Instant.parse("2026-07-28T00:00:00Z"));
        when(user.isEnabled()).thenReturn(false);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));
        authenticate(Instant.parse("2026-07-28T01:00:00Z"));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);

        filter.doFilter(request("/api/v1/notes"), response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("登录已失效");
        verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void allowsTokenIssuedAtSessionValidityBoundary() throws Exception {
        var         boundary = Instant.parse("2026-07-28T00:00:30.900Z");
        var user = mock(AdminUserEntity.class);
        when(user.getSessionsValidFrom()).thenReturn(boundary);
        when(user.isEnabled()).thenReturn(true);
        when(repository.findByUsername("admin")).thenReturn(Optional.of(user));
        authenticate(boundary.truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        var request = request("/api/v1/notes");

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    private static MockHttpServletRequest request(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static void authenticate(Instant issuedAt) {
        var jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("admin")
            .issuedAt(issuedAt)
            .expiresAt(issuedAt.plusSeconds(900))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
