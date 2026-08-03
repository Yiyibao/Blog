package com.yubai.blog.admin.ai;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "ai_generated_images")
public class AiGeneratedImageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "generation_id", nullable = false)
    private UUID generationId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false, length = 32)
    private String provider;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column
    private Integer width;

    @Column
    private Integer height;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiGeneratedImageEntity() {}

    public static AiGeneratedImageEntity create(Long sessionId, UUID generationId, String provider, String model,
                                                 String prompt, String storageKey, String fileName, String mediaType,
                                                 long byteSize, String sha256, Integer width, Integer height) {
        var entity = new AiGeneratedImageEntity();
        entity.publicId = UUID.randomUUID();
        entity.generationId = generationId;
        entity.sessionId = sessionId;
        entity.provider = provider;
        entity.model = model;
        entity.prompt = prompt;
        entity.storageKey = storageKey;
        entity.fileName = fileName;
        entity.mediaType = mediaType;
        entity.byteSize = byteSize;
        entity.sha256 = sha256;
        entity.width = width;
        entity.height = height;
        return entity;
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public UUID getPublicId() { return publicId; }
    public UUID getGenerationId() { return generationId; }
    public Long getSessionId() { return sessionId; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public String getPrompt() { return prompt; }
    public String getStorageKey() { return storageKey; }
    public String getFileName() { return fileName; }
    public String getMediaType() { return mediaType; }
    public long getByteSize() { return byteSize; }
    public String getSha256() { return sha256; }
    public Integer getWidth() { return width; }
    public Integer getHeight() { return height; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
