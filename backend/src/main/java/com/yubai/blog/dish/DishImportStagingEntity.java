package com.yubai.blog.dish;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "dish_import_staging")
public class DishImportStagingEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private UUID token;

    @Column(name = "recipe_json", nullable = false, columnDefinition = "text")
    private String recipeJson;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "media_type", length = 100)
    private String mediaType;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private boolean consumed;

    @Column(nullable = false)
    private boolean cancelled;

    @Version
    @Column(nullable = false)
    private int version;

    protected DishImportStagingEntity() {}

    public static DishImportStagingEntity create(String recipeJson, String storageKey, String mediaType, Instant expiresAt) {
        var entity = new DishImportStagingEntity();
        entity.token = UUID.randomUUID();
        entity.recipeJson = recipeJson;
        entity.storageKey = storageKey;
        entity.mediaType = mediaType;
        entity.expiresAt = expiresAt;
        entity.consumed = false;
        entity.cancelled = false;
        return entity;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public UUID getToken() { return token; }
    public String getRecipeJson() { return recipeJson; }
    public String getStorageKey() { return storageKey; }
    public String getMediaType() { return mediaType; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public boolean isConsumed() { return consumed; }
    public void setConsumed(boolean consumed) { this.consumed = consumed; }
    public boolean isCancelled() { return cancelled; }
    public void setCancelled(boolean cancelled) { this.cancelled = cancelled; }
}
