package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.OctetSequenceKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

/** P2-10：JWT 拒收路径——过期 token 与异钥伪造签名都必须被解码器拒绝。 */
class JwtServiceRejectionTest {

    private static SecretKey key(String secret) {
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    private static JwtService service(SecretKey key, Duration ttl) {
        var jwk = new OctetSequenceKey.Builder(key).build();
        JWKSource<SecurityContext> source = (selector, context) -> selector.select(new JWKSet(jwk));
        return new JwtService(new NimbusJwtEncoder(source), ttl, Duration.ofHours(24));
    }

    private static NimbusJwtDecoder decoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    private static AdminUserEntity user() {
        var entity = mock(AdminUserEntity.class);
        when(entity.getId()).thenReturn(1L);
        when(entity.getUsername()).thenReturn("boss");
        when(entity.getRole()).thenReturn(AdminUserRole.ADMIN);
        when(entity.getDisplayName()).thenReturn("站长");
        when(entity.getSessionsValidFrom()).thenReturn(Instant.now());
        return entity;
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        SecretKey key = key("0123456789abcdef0123456789abcdef");
        // JWT 时间戳按秒序列化且签发要求 exp 严格晚于 iat——用最小合法 TTL（1 秒）等到过期，
        // 再以零时钟偏移解码器验证拒收（默认解码器有 60 秒容忍）
        var response = service(key, Duration.ofSeconds(1)).issue(user(), false);
        Thread.sleep(1200);
        var strictDecoder = decoder(key);
        strictDecoder.setJwtValidator(new org.springframework.security.oauth2.jwt.JwtTimestampValidator(Duration.ZERO));
        assertThatThrownBy(() -> strictDecoder.decode(response.token()))
            .isInstanceOf(JwtException.class)
            .hasMessageContaining("expired");
    }

    @Test
    void tokenSignedWithDifferentKeyIsRejected() {
        var response = service(key("0123456789abcdef0123456789abcdef"), Duration.ofHours(2)).issue(user(), false);
        assertThatThrownBy(() -> decoder(key("fedcba9876543210fedcba9876543210")).decode(response.token()))
            .isInstanceOf(JwtException.class);
    }
}
