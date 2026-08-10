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
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_tasks")
public class AiTaskEntity {
    @Id private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(name = "task_type", nullable = false, length = 40)
    private String taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiTaskStatus status;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_type", length = 32)
    private String providerType;

    @Column(length = 160)
    private String model;

    @Column(name = "idempotency_key", nullable = false, length = 160)
    private String idempotencyKey;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Version private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiTaskEntity() {}

    public static AiTaskEntity create(
            String owner,
            Long sessionId,
            String taskType,
            Long providerId,
            String model,
            String idempotencyKey) {
        var entity = new AiTaskEntity();
        entity.id = UUID.randomUUID();
        entity.owner = owner;
        entity.sessionId = sessionId;
        entity.taskType = taskType;
        entity.status = AiTaskStatus.QUEUED;
        entity.providerId = providerId;
        entity.model = model;
        entity.idempotencyKey = idempotencyKey;
        return entity;
    }

    public void start(String providerType, String resolvedModel) {
        requireStatus(AiTaskStatus.QUEUED);
        status = AiTaskStatus.RUNNING;
        startedAt = Instant.now();
        this.providerType = providerType;
        this.model = resolvedModel;
        clearError();
    }

    public void complete() {
        requireStatus(AiTaskStatus.RUNNING);
        status = AiTaskStatus.COMPLETED;
        finishedAt = Instant.now();
        clearError();
    }

    public void fail(String code, String message) {
        if (status.isTerminal()) return;
        status = AiTaskStatus.FAILED;
        finishedAt = Instant.now();
        errorCode = truncate(code, 80);
        errorMessage = truncate(message, 500);
    }

    public void cancel() {
        if (status.isTerminal()) return;
        status = AiTaskStatus.CANCELLED;
        finishedAt = Instant.now();
        errorCode = "CANCELLED";
        errorMessage = null;
    }

    private void clearError() {
        errorCode = null;
        errorMessage = null;
    }

    private void requireStatus(AiTaskStatus expected) {
        if (status != expected) {
            throw new IllegalStateException("Invalid AI task transition from " + status);
        }
    }

    private static String truncate(String value, int length) {
        if (value == null) return null;
        return value.length() <= length ? value : value.substring(0, length);
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

    public Long getSessionId() {
        return sessionId;
    }

    public String getTaskType() {
        return taskType;
    }

    public AiTaskStatus getStatus() {
        return status;
    }

    public Long getProviderId() {
        return providerId;
    }

    public String getProviderType() {
        return providerType;
    }

    public String getModel() {
        return model;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
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
