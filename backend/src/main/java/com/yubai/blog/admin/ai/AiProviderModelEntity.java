package com.yubai.blog.admin.ai;

import com.yubai.blog.ai.AiProviderCapability;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "ai_provider_models")
public class AiProviderModelEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private Long providerId;

    @Column(nullable = false, length = 160)
    private String model;

    @Column(nullable = false, length = 1000)
    private String capabilities;

    @Column(name = "reasoning_efforts", nullable = false, length = 160)
    private String reasoningEfforts;

    @Column(nullable = false)
    private boolean enabled = true;

    @Version private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiProviderModelEntity() {}

    public static AiProviderModelEntity create(
            Long providerId,
            String model,
            Collection<AiProviderCapability> capabilities,
            Collection<String> reasoningEfforts,
            boolean enabled) {
        var entity = new AiProviderModelEntity();
        entity.providerId = providerId;
        entity.model = normalizeModel(model);
        entity.updateCapabilities(capabilities, reasoningEfforts, enabled);
        return entity;
    }

    public void updateCapabilities(
            Collection<AiProviderCapability> capabilities,
            Collection<String> reasoningEfforts,
            boolean enabled) {
        var normalizedCapabilities =
                capabilities == null || capabilities.isEmpty()
                        ? Set.of(AiProviderCapability.TEXT)
                        : EnumSet.copyOf(capabilities);
        this.capabilities =
                normalizedCapabilities.stream()
                        .map(Enum::name)
                        .sorted()
                        .collect(Collectors.joining(","));
        var normalizedReasoning =
                reasoningEfforts == null || reasoningEfforts.isEmpty()
                        ? Set.of("none")
                        : reasoningEfforts.stream()
                                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                                .filter(value -> !value.isBlank())
                                .distinct()
                                .sorted()
                                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        this.reasoningEfforts = String.join(",", normalizedReasoning);
        this.enabled = enabled;
    }

    private static String normalizeModel(String value) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Model is required");
        return value.trim();
    }

    public Set<AiProviderCapability> capabilities() {
        var result = EnumSet.noneOf(AiProviderCapability.class);
        for (var value : capabilities.split(",")) {
            try {
                result.add(AiProviderCapability.valueOf(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // Ignore unknown future capabilities when reading an older server.
            }
        }
        return Set.copyOf(result);
    }

    public Set<String> reasoningEfforts() {
        return java.util.Arrays.stream(reasoningEfforts.split(","))
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
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

    public Long getId() {
        return id;
    }

    public Long getProviderId() {
        return providerId;
    }

    public String getModel() {
        return model;
    }

    public String getCapabilities() {
        return capabilities;
    }

    public String getReasoningEfforts() {
        return reasoningEfforts;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
