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
@Table(name = "ai_files")
public class AiFileEntity {
    @Id private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(name = "storage_key", nullable = false, unique = true, length = 512)
    private String storageKey;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    @Column(name = "media_type", nullable = false, length = 120)
    private String mediaType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiFileStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiFileRetention retention;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "reference_count", nullable = false)
    private int referenceCount;

    @Column(name = "extracted_text", columnDefinition = "text")
    private String extractedText;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiFileEntity() {}

    public static AiFileEntity ready(
            UUID id,
            String owner,
            String storageKey,
            String originalName,
            String mediaType,
            long sizeBytes,
            String sha256,
            AiFileRetention retention,
            Instant expiresAt,
            String extractedText) {
        var entity = new AiFileEntity();
        entity.id = id;
        entity.owner = owner;
        entity.storageKey = storageKey;
        entity.originalName = originalName;
        entity.mediaType = mediaType;
        entity.sizeBytes = sizeBytes;
        entity.sha256 = sha256;
        entity.status = AiFileStatus.READY;
        entity.retention = retention;
        entity.expiresAt = expiresAt;
        entity.extractedText = extractedText;
        return entity;
    }

    public void incrementReference() {
        if (status != AiFileStatus.READY) throw new IllegalStateException("AI file is not ready");
        referenceCount += 1;
    }

    public void forget() {
        status = AiFileStatus.DELETED;
        extractedText = null;
        referenceCount = 0;
    }

    public void expire() {
        if (status == AiFileStatus.READY) status = AiFileStatus.EXPIRED;
        extractedText = null;
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

    public String getStorageKey() {
        return storageKey;
    }

    public String getOriginalName() {
        return originalName;
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

    public AiFileStatus getStatus() {
        return status;
    }

    public AiFileRetention getRetention() {
        return retention;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public int getReferenceCount() {
        return referenceCount;
    }

    public String getExtractedText() {
        return extractedText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
