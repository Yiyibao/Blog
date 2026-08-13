package com.yubai.blog.post;

import com.yubai.blog.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostPreviewService {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Duration MAX_TTL = Duration.ofHours(1);

    private final PostRepository postRepository;
    private final PostPreviewTokenRepository tokenRepository;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public PostPreviewService(
            PostRepository postRepository,
            PostPreviewTokenRepository tokenRepository,
            Clock clock) {
        this.postRepository = postRepository;
        this.tokenRepository = tokenRepository;
        this.clock = clock;
    }

    @Transactional
    public CreatedToken create(long postId, Integer ttlMinutes, String actor) {
        var post = postRepository.findById(postId).orElseThrow(() -> missing(postId));
        var ttl = ttlMinutes == null ? DEFAULT_TTL : Duration.ofMinutes(ttlMinutes);
        if (ttl.isNegative() || ttl.isZero() || ttl.compareTo(MAX_TTL) > 0) {
            throw new IllegalArgumentException(
                    "preview token TTL must be between 1 and 60 minutes");
        }
        var raw = new byte[32];
        random.nextBytes(raw);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var expiresAt = clock.instant().plus(ttl);
        var entity =
                tokenRepository.save(
                        PostPreviewTokenEntity.create(
                                post.getId(),
                                sha256(token),
                                post.getVersion(),
                                expiresAt,
                                actor == null || actor.isBlank() ? "unknown" : actor));
        return new CreatedToken(entity.getId(), token, expiresAt, post.getVersion());
    }

    @Transactional(readOnly = true)
    public PostResponse resolve(long postId, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) throw missing(postId);
        var token =
                tokenRepository
                        .findByTokenHash(sha256(rawToken))
                        .orElseThrow(() -> missing(postId));
        var post = postRepository.findById(postId).orElseThrow(() -> missing(postId));
        var now = clock.instant();
        if (token.getPostId() != postId
                || token.getRevokedAt() != null
                || !token.getExpiresAt().isAfter(now)
                || token.getPostVersion() != post.getVersion()) {
            throw missing(postId);
        }
        return PostResponse.from(post);
    }

    @Transactional
    public void revoke(long postId, UUID tokenId) {
        var token =
                tokenRepository
                        .findByIdAndPostId(tokenId, postId)
                        .orElseThrow(() -> missing(postId));
        token.revoke(clock.instant());
    }

    private static NotFoundException missing(long postId) {
        return new NotFoundException("预览链接不存在或已失效：" + postId);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record CreatedToken(UUID id, String token, Instant expiresAt, long postVersion) {}
}
