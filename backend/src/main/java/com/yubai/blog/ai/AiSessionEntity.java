package com.yubai.blog.ai;

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
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "ai_sessions")
public class AiSessionEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(length = 160)
    private String title;

    @Column(nullable = false, length = 32)
    private String mode = "WORKSPACE";

    @Column(name = "project_id")
    private Long projectId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AiSessionStatus status = AiSessionStatus.ACTIVE;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(columnDefinition = "text")
    private String summary;

    @Version private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiSessionEntity() {}

    public static AiSessionEntity create(String owner, String title, String mode) {
        return create(owner, title, mode, null);
    }

    public static AiSessionEntity create(String owner, String title, String mode, Long projectId) {
        var entity = new AiSessionEntity();
        entity.owner = owner;
        entity.title = normalizeTitle(title);
        entity.mode = mode == null || mode.isBlank() ? "WORKSPACE" : mode.trim();
        entity.projectId = projectId;
        return entity;
    }

    public void updateTitle(String title) {
        if (status == AiSessionStatus.DELETED) {
            throw new IllegalStateException("Deleted AI session cannot be renamed");
        }
        this.title = normalizeTitle(title);
    }

    public void moveToProject(Long projectId) {
        if (status == AiSessionStatus.DELETED) {
            throw new IllegalStateException("Deleted AI session cannot be moved");
        }
        this.projectId = projectId;
        this.status = AiSessionStatus.ACTIVE;
        this.archivedAt = null;
    }

    public void archive() {
        if (status == AiSessionStatus.DELETED) return;
        status = AiSessionStatus.ARCHIVED;
        archivedAt = Instant.now();
    }

    public void delete() {
        status = AiSessionStatus.DELETED;
        archivedAt = Instant.now();
    }

    public void updateSummary(String summary) {
        this.summary = summary == null || summary.isBlank() ? null : summary.trim();
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

    private static String normalizeTitle(String title) {
        if (title == null || title.isBlank()) return null;
        var normalized = title.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 160 ? normalized : normalized.substring(0, 160);
    }

    public Long getId() {
        return id;
    }

    public String getOwner() {
        return owner;
    }

    public String getTitle() {
        return title;
    }

    public String getMode() {
        return mode;
    }

    public Long getProjectId() {
        return projectId;
    }

    public AiSessionStatus getStatus() {
        return status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public String getSummary() {
        return summary;
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
