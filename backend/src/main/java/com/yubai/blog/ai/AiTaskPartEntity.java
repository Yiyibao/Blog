package com.yubai.blog.ai;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_task_parts")
public class AiTaskPartEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private UUID taskId;

    @Column(nullable = false)
    private int sequence;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private AiPartRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private AiPartKind kind;

    @Column(name = "text_content", columnDefinition = "text")
    private String textContent;

    @Column(columnDefinition = "text")
    private String payload;

    @Column(name = "file_id")
    private UUID fileId;

    @Column(name = "artifact_id")
    private UUID artifactId;

    @Column(name = "source_ref", length = 500)
    private String sourceRef;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiTaskPartEntity() {}

    public static AiTaskPartEntity create(
            UUID taskId,
            int sequence,
            AiPartRole role,
            AiPartKind kind,
            String textContent,
            String payload,
            UUID fileId,
            UUID artifactId,
            String sourceRef) {
        var entity = new AiTaskPartEntity();
        entity.taskId = taskId;
        entity.sequence = sequence;
        entity.role = role;
        entity.kind = kind;
        entity.textContent = textContent;
        entity.payload = payload;
        entity.fileId = fileId;
        entity.artifactId = artifactId;
        entity.sourceRef = sourceRef;
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

    public int getSequence() {
        return sequence;
    }

    public AiPartRole getRole() {
        return role;
    }

    public AiPartKind getKind() {
        return kind;
    }

    public String getTextContent() {
        return textContent;
    }

    public String getPayload() {
        return payload;
    }

    public UUID getFileId() {
        return fileId;
    }

    public UUID getArtifactId() {
        return artifactId;
    }

    public String getSourceRef() {
        return sourceRef;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
