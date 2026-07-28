package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/** FD-6：roles 不再硬编码——解 JWT 断言精确 claims，防回归成"谁登录都是 ADMIN"。 */
class JwtServiceTest {

    private JwtService service;
    private NimbusJwtDecoder decoder;

    @BeforeEach
    void setUp() {
        SecretKey key = new SecretKeySpec(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        var jwk = new OctetSequenceKey.Builder(key).build();
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(jwk));
        service = new JwtService(new NimbusJwtEncoder(source), Duration.ofHours(2), Duration.ofHours(24));
        decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private AdminUserEntity user(long id, String username, AdminUserRole role, String displayName) {
        var entity = mock(AdminUserEntity.class);
        when(entity.getId()).thenReturn(id);
        when(entity.getUsername()).thenReturn(username);
        when(entity.getRole()).thenReturn(role);
        when(entity.getDisplayName()).thenReturn(displayName);
        when(entity.getSessionsValidFrom()).thenReturn(Instant.now());
        return entity;
    }

    @Test
    void partnerTokenCarriesExactPartnerRoleAndIdentityClaims() {
        var response = service.issue(user(7L, "partner", AdminUserRole.PARTNER, "小伙伴"), false);

        assertThat(response.role()).isEqualTo("PARTNER");
        assertThat(response.displayName()).isEqualTo("小伙伴");
        assertThat(response.username()).isEqualTo("partner");
        assertThat(response.tokenType()).isEqualTo("Bearer");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        var jwt = decoder.decode(response.token());
        assertThat(jwt.getSubject()).isEqualTo("partner");
        assertThat(jwt.getClaimAsStringList("roles")).as("精确断言，不允许残留 ADMIN").containsExactly("PARTNER");
        assertThat(jwt.<Long>getClaim("uid")).isEqualTo(7L);
        assertThat(jwt.getClaimAsString("name")).isEqualTo("小伙伴");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("yubai-blog");
    }

    @Test
    void adminTokenCarriesExactAdminRole() {
        var response = service.issue(user(1L, "boss", AdminUserRole.ADMIN, "站长"), false);
        var jwt = decoder.decode(response.token());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("ADMIN");
        assertThat(jwt.<Long>getClaim("uid")).isEqualTo(1L);
    }

    @Test
    void rememberLoginGetsTheLongTtl() {
        // FD-9：普通 2h、保持登录 24h——两档都取自构造参数，防配置串线
        var normal = service.issue(user(1L, "boss", AdminUserRole.ADMIN, "站长"), false);
        var remembered = service.issue(user(1L, "boss", AdminUserRole.ADMIN, "站长"), true);
        assertThat(normal.expiresAt()).isBefore(Instant.now().plus(Duration.ofHours(3)));
        assertThat(remembered.expiresAt()).isAfter(Instant.now().plus(Duration.ofHours(23)));
        assertThat(remembered.expiresAt()).isBefore(Instant.now().plus(Duration.ofHours(25)));
    }
}
