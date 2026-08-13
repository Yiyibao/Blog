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
@Table(name = "ai_action_proposals")
public class AiActionProposalEntity {
    @Id private UUID id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "action_type", nullable = false, length = 80)
    private String actionType;

    @Column(name = "target_type", length = 80)
    private String targetType;

    @Column(name = "target_id", length = 128)
    private String targetId;

    @Column(name = "target_version")
    private Long targetVersion;

    @Column(nullable = false, columnDefinition = "jsonb")
    private String arguments;

    @Column(name = "arguments_hash", nullable = false, length = 64)
    private String argumentsHash;

    @Column(name = "nonce_hash", nullable = false, length = 64)
    private String nonceHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiActionProposalStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "approved_by", length = 128)
    private String approvedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @Column(name = "rejected_by", length = 128)
    private String rejectedBy;

    @Column(name = "rejected_reason", length = 500)
    private String rejectedReason;

    protected AiActionProposalEntity() {}

    static AiActionProposalEntity create(
            String owner,
            UUID taskId,
            String actionType,
            String targetType,
            String targetId,
            Long targetVersion,
            String arguments,
            String argumentsHash,
            String nonceHash,
            Instant expiresAt) {
        var entity = new AiActionProposalEntity();
        entity.id = UUID.randomUUID();
        entity.owner = owner;
        entity.taskId = taskId;
        entity.actionType = actionType;
        entity.targetType = targetType;
        entity.targetId = targetId;
        entity.targetVersion = targetVersion;
        entity.arguments = arguments;
        entity.argumentsHash = argumentsHash;
        entity.nonceHash = nonceHash;
        entity.status = AiActionProposalStatus.PROPOSED;
        entity.expiresAt = expiresAt;
        return entity;
    }

    void approve(String actor, Instant now) {
        status = AiActionProposalStatus.APPROVED;
        approvedBy = actor;
        approvedAt = now;
    }

    void reject(String actor, String reason, Instant now) {
        status = AiActionProposalStatus.REJECTED;
        rejectedBy = actor;
        rejectedReason = reason;
        rejectedAt = now;
    }

    void expire() {
        status = AiActionProposalStatus.EXPIRED;
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
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

    public String getActionType() {
        return actionType;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public Long getTargetVersion() {
        return targetVersion;
    }

    public String getArguments() {
        return arguments;
    }

    public String getArgumentsHash() {
        return argumentsHash;
    }

    public String getNonceHash() {
        return nonceHash;
    }

    public AiActionProposalStatus getStatus() {
        return status;
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

    public Instant getApprovedAt() {
        return approvedAt;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public Instant getRejectedAt() {
        return rejectedAt;
    }

    public String getRejectedBy() {
        return rejectedBy;
    }

    public String getRejectedReason() {
        return rejectedReason;
    }
}
