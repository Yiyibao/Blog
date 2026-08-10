package com.yubai.blog.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_task_events")
public class AiTaskEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false)
    private long sequence;

    @Column(name = "event_type", nullable = false, length = 80)
    private String eventType;

    @Column(name = "sanitized_payload", nullable = false, columnDefinition = "text")
    private String sanitizedPayload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiTaskEventEntity() {}

    public static AiTaskEventEntity create(
            UUID taskId, long sequence, String eventType, String sanitizedPayload) {
        var entity = new AiTaskEventEntity();
        entity.taskId = taskId;
        entity.sequence = sequence;
        entity.eventType = eventType;
        entity.sanitizedPayload = sanitizedPayload == null ? "{}" : sanitizedPayload;
        return entity;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getTaskId() {
        return taskId;
    }

    public long getSequence() {
        return sequence;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSanitizedPayload() {
        return sanitizedPayload;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
