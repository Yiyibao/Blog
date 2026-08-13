package com.yubai.blog.post;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "post_preview_tokens")
public class PostPreviewTokenEntity {
    @Id private UUID id;

    @Column(name = "post_id", nullable = false)
    private long postId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Column(name = "post_version", nullable = false)
    private long postVersion;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostPreviewTokenEntity() {}

    static PostPreviewTokenEntity create(
            long postId, String tokenHash, long postVersion, Instant expiresAt, String createdBy) {
        var entity = new PostPreviewTokenEntity();
        entity.id = UUID.randomUUID();
        entity.postId = postId;
        entity.tokenHash = tokenHash;
        entity.postVersion = postVersion;
        entity.expiresAt = expiresAt;
        entity.createdBy = createdBy;
        return entity;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    void revoke(Instant now) {
        revokedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public long getPostId() {
        return postId;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public long getPostVersion() {
        return postVersion;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
