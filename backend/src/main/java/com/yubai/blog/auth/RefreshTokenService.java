package com.yubai.blog.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RefreshTokenService {
    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository repository;
    private final Duration ttl;
    private final Duration rememberTtl;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(RefreshTokenRepository repository,
                               @Value("${app.jwt.refresh-ttl:P7D}") Duration ttl,
                               @Value("${app.jwt.refresh-remember-ttl:P30D}") Duration rememberTtl,
                               Clock clock) {
        this.repository = repository;
        this.ttl = ttl;
        this.rememberTtl = rememberTtl;
        this.clock = clock;
    }

    public static String generateRawToken() {
        var bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static String hashToken(String rawToken) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    public record IssuedToken(String raw, RefreshTokenEntity entity) {}

    @Transactional
    public IssuedToken issue(Long userId, boolean remember) {
        var raw = generateRawToken();
        var hash = hashToken(raw);
        var expiresAt = Instant.now(clock).plus(remember ? rememberTtl : ttl);
        var entity = new RefreshTokenEntity(hash, userId, UUID.randomUUID(), expiresAt);
        repository.save(entity);
        return new IssuedToken(raw, entity);
    }

    public record RotateResult(String rawToken, RefreshTokenEntity newEntity) {}

    @Transactional(noRollbackFor = RefreshTokenException.class)
    public RotateResult rotate(String rawToken) {
        var hash = hashToken(rawToken);
        var entity = repository.findByTokenHash(hash).orElse(null);
        if (entity == null) {
            throw new RefreshTokenException("refresh token not found");
        }
        if (entity.getExpiresAt().isBefore(Instant.now(clock))) {
            throw new RefreshTokenException("refresh token expired");
        }

        var now = Instant.now(clock);
        int updated = repository.atomicRevokeAndMarkUsed(entity.getId(), now);
        if (updated == 0) {
            repository.revokeFamily(entity.getFamily());
            log.warn("refresh token replay detected, family={} userId={}", entity.getFamily(), entity.getUserId());
            throw new RefreshTokenException("refresh token already used, family revoked");
        }

        var newRaw = generateRawToken();
        var newHash = hashToken(newRaw);
        var newEntity = new RefreshTokenEntity(newHash, entity.getUserId(), entity.getFamily(),
            entity.getExpiresAt());
        repository.save(newEntity);
        return new RotateResult(newRaw, newEntity);
    }

    @Transactional
    public void revokeAllByUserId(Long userId) {
        repository.revokeByUserId(userId);
    }

    @Transactional
    public void revoke(String rawToken) {
        var hash = hashToken(rawToken);
        repository.findByTokenHash(hash).ifPresent(entity -> {
            entity.setRevoked(true);
            repository.save(entity);
        });
    }

    public static class RefreshTokenException extends RuntimeException {
        public RefreshTokenException(String message) {
            super(message);
        }
    }
}
