package com.yubai.blog.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_memories")
public class AiMemoryEntity {
    @Id private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(nullable = false, length = 80)
    private String scope;

    @Column(nullable = false, length = 40)
    private String kind;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "source_task_id")
    private UUID sourceTaskId;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiMemoryStatus status;

    @Column(precision = 5, scale = 4)
    private BigDecimal confidence;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Version private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiMemoryEntity() {}

    public static AiMemoryEntity create(
            String owner,
            String scope,
            String kind,
            String content,
            UUID sourceTaskId,
            String sourceRef,
            AiMemoryStatus status,
            BigDecimal confidence,
            Instant expiresAt) {
        var entity = new AiMemoryEntity();
        entity.id = UUID.randomUUID();
        entity.owner = owner;
        entity.scope = scope;
        entity.kind = kind;
        entity.content = content;
        entity.sourceTaskId = sourceTaskId;
        entity.sourceRef = sourceRef;
        entity.status = status;
        entity.confidence = confidence;
        entity.expiresAt = expiresAt;
        return entity;
    }

    public void confirm() {
        if (status != AiMemoryStatus.PROPOSED) {
            throw new IllegalStateException("Only proposed memories can be confirmed");
        }
        status = AiMemoryStatus.ACTIVE;
    }

    public void update(String scope, String kind, String content, Instant expiresAt) {
        if (status == AiMemoryStatus.DELETED) throw new IllegalStateException("Memory was deleted");
        this.scope = scope;
        this.kind = kind;
        this.content = content;
        this.expiresAt = expiresAt;
    }

    public void disable() {
        if (status == AiMemoryStatus.ACTIVE) status = AiMemoryStatus.DISABLED;
    }

    public void enable() {
        if (status == AiMemoryStatus.DISABLED) status = AiMemoryStatus.ACTIVE;
    }

    public void reject() {
        if (status == AiMemoryStatus.PROPOSED) status = AiMemoryStatus.REJECTED;
    }

    public void forget() {
        status = AiMemoryStatus.DELETED;
        content = null;
        sourceRef = null;
        confidence = null;
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

    public String getScope() {
        return scope;
    }

    public String getKind() {
        return kind;
    }

    public String getContent() {
        return content;
    }

    public UUID getSourceTaskId() {
        return sourceTaskId;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public AiMemoryStatus getStatus() {
        return status;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public Instant getExpiresAt() {
        return expiresAt;
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
