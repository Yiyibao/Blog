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

    protected DishAssetEntity() {}

    public static DishAssetEntity create(
            String storageKey,
            String fileName,
            String mediaType,
            long byteSize,
            String sha256,
            Integer width,
            Integer height) {
        var entity = new DishAssetEntity();
        entity.publicId = UUID.randomUUID();
        entity.storageKey = storageKey;
        entity.fileName = fileName;
        entity.mediaType = mediaType;
        entity.byteSize = byteSize;
        entity.sha256 = sha256;
        entity.width = width;
        entity.height = height;
        return entity;
    }

    public static DishAssetEntity createWithContent(
            String fileName,
            String mediaType,
            byte[] content,
            String sha256,
            Integer width,
            Integer height) {
        var entity = new DishAssetEntity();
        entity.publicId = UUID.randomUUID();
        entity.fileName = fileName;
        entity.mediaType = mediaType;
        entity.byteSize = content.length;
        entity.content = content;
        entity.sha256 = sha256;
        entity.width = width;
        entity.height = height;
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
}
