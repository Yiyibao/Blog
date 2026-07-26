package com.yubai.blog.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final Duration ttl;

    public JwtService(JwtEncoder encoder, @Value("${app.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.ttl = ttl;
    }

    /** FD-6：roles 不再硬编码 ADMIN，改读账号实体；uid 供 kitchen 署名/限流，name 供前端问候。 */
    public LoginResponse issue(AdminUserEntity user) {
        var issuedAt = Instant.now();
        var expiresAt = issuedAt.plus(ttl);
        var claims = JwtClaimsSet.builder()
            .issuer("yubai-blog")
            .issuedAt(issuedAt)
            .expiresAt(expiresAt)
            .subject(user.getUsername())
            .claim("roles", List.of(user.getRole().name()))
            .claim("uid", user.getId())
            .claim("name", user.getDisplayName())
            .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        var token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new LoginResponse(token, "Bearer", user.getUsername(),
            user.getRole().name(), user.getDisplayName(), expiresAt);
    }
}
