package com.yubai.blog.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_artifacts")
public class AiArtifactEntity {
    @Id private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(name = "storage_key", nullable = false, unique = true, length = 512)
    private String storageKey;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(name = "media_type", nullable = false, length = 120)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiArtifactStatus status;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiArtifactEntity() {}

    public static AiArtifactEntity ready(
            UUID id,
            String owner,
            UUID taskId,
            String storageKey,
            String name,
            String mediaType,
            long sizeBytes,
            String sha256,
            Instant expiresAt) {
        var entity = new AiArtifactEntity();
        entity.id = id;
        entity.owner = owner;
        entity.taskId = taskId;
        entity.storageKey = storageKey;
        entity.name = name;
        entity.mediaType = mediaType;
        entity.sizeBytes = sizeBytes;
        entity.sha256 = sha256;
        entity.status = AiArtifactStatus.READY;
        entity.expiresAt = expiresAt;
        return entity;
    }

    public void expire() {
        if (status == AiArtifactStatus.READY) status = AiArtifactStatus.EXPIRED;
    }

    public void delete() {
        status = AiArtifactStatus.DELETED;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public String getName() {
        return name;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public AiArtifactStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
