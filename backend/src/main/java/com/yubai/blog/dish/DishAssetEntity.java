package com.yubai.blog.dish;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "dish_assets")
public class DishAssetEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "dish_id")
    private Long dishId;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column private Integer width;

    @Column private Integer height;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "alt_text", length = 240)
    private String altText;

    @Column(name = "source_url", length = 500)
    private String sourceUrl;

    @Column(name = "license", length = 160)
    private String license;

    @Column(name = "reference_count", nullable = false)
    private int referenceCount;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy = "admin";

    protected DishAssetEntity() {}

    public static DishAssetEntity create(
            String storageKey,
            String fileName,
            String mediaType,
            long byteSize,
            String sha256,
            Integer width,
            Integer height) {
        return create("admin", storageKey, fileName, mediaType, byteSize, sha256, width, height);
    }

    public static DishAssetEntity create(
            String owner,
            String storageKey,
            String fileName,
            String mediaType,
            long byteSize,
            String sha256,
            Integer width,
            Integer height) {
        var entity = new DishAssetEntity();
        entity.publicId = UUID.randomUUID();
        entity.owner = owner;
        entity.storageKey = storageKey;
        entity.fileName = fileName;
        entity.mediaType = mediaType;
        entity.byteSize = byteSize;
        entity.sha256 = sha256;
        entity.width = width;
        entity.height = height;
        entity.altText = fileName;
        entity.createdBy = owner;
        entity.expiresAt = Instant.now().plusSeconds(3600);
        return entity;
    }

    public static DishAssetEntity createWithContent(
            String fileName,
            String mediaType,
            byte[] content,
            String sha256,
            Integer width,
            Integer height) {
        return createWithContent("admin", fileName, mediaType, content, sha256, width, height);
    }

    public static DishAssetEntity createWithContent(
            String owner,
            String fileName,
            String mediaType,
            byte[] content,
            String sha256,
            Integer width,
            Integer height) {
        var entity = new DishAssetEntity();
        entity.publicId = UUID.randomUUID();
        entity.owner = owner;
        entity.fileName = fileName;
        entity.mediaType = mediaType;
        entity.byteSize = content.length;
        entity.content = content;
        entity.sha256 = sha256;
        entity.width = width;
        entity.height = height;
        entity.altText = fileName;
        entity.createdBy = owner;
        entity.expiresAt = Instant.now().plusSeconds(3600);
        return entity;
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public Long getDishId() {
        return dishId;
    }

    public void setDishId(Long dishId) {
        this.dishId = dishId;
        if (dishId != null) this.expiresAt = null;
    }

    public String getOwner() {
        return owner;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public byte[] getContent() {
        return content;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public String getSha256() {
        return sha256;
    }

    public Integer getWidth() {
        return width;
    }

    public Integer getHeight() {
        return height;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getAltText() {
        return altText;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public String getLicense() {
        return license;
    }

    public int getReferenceCount() {
        return referenceCount;
    }

    public String getCreatedBy() {
        return createdBy;
    }
}
