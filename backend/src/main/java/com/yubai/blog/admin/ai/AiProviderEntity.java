package com.yubai.blog.admin.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "ai_providers")
public class AiProviderEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 60)
    private String name;

    @Column(name = "base_url", nullable = false, columnDefinition = "text")
    private String baseUrl;

    @Column(name = "api_key_encrypted", columnDefinition = "text")
    private String apiKeyEncrypted;

    /** 逗号分隔的可用模型列表；空串表示不限制。 */
    @Column(nullable = false, columnDefinition = "text")
    private String models = "";

    @Column(name = "default_model", nullable = false, length = 120)
    private String defaultModel;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault;

    @Column(name = "daily_request_limit", nullable = false)
    private int dailyRequestLimit = 200;

    @Column(name = "daily_token_limit", nullable = false)
    private int dailyTokenLimit = 200_000;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider_type", nullable = false, length = 30)
    private AiProviderType providerType = AiProviderType.OPENAI_COMPATIBLE;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiProviderEntity() {
    }

    public static AiProviderEntity create(String name, String baseUrl, String apiKeyEncrypted,
                                           String models, String defaultModel, boolean enabled,
                                           int dailyRequestLimit, int dailyTokenLimit,
                                           AiProviderType providerType) {
        var entity = new AiProviderEntity();
        entity.name = name;
        entity.baseUrl = baseUrl;
        entity.apiKeyEncrypted = apiKeyEncrypted;
        entity.models = models == null ? "" : models;
        entity.defaultModel = defaultModel;
        entity.enabled = enabled;
        entity.dailyRequestLimit = dailyRequestLimit;
        entity.dailyTokenLimit = dailyTokenLimit;
        entity.providerType = providerType;
        return entity;
    }

    public void update(String name, String baseUrl, String models, String defaultModel,
                       boolean enabled, int dailyRequestLimit, int dailyTokenLimit,
                       AiProviderType providerType) {
        this.name = name;
        this.baseUrl = baseUrl;
        this.models = models == null ? "" : models;
        this.defaultModel = defaultModel;
        this.enabled = enabled;
        this.dailyRequestLimit = dailyRequestLimit;
        this.dailyTokenLimit = dailyTokenLimit;
        this.providerType = providerType;
    }

    public void replaceApiKey(String apiKeyEncrypted) {
        this.apiKeyEncrypted = apiKeyEncrypted;
    }

    public void markDefault(boolean isDefault) {
        this.isDefault = isDefault;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getBaseUrl() { return baseUrl; }
    public String getApiKeyEncrypted() { return apiKeyEncrypted; }
    public String getModels() { return models; }
    public String getDefaultModel() { return defaultModel; }
    public boolean isEnabled() { return enabled; }
    public boolean isDefault() { return isDefault; }
    public int getDailyRequestLimit() { return dailyRequestLimit; }
    public int getDailyTokenLimit() { return dailyTokenLimit; }
    public AiProviderType getProviderType() { return providerType; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
