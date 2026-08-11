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
@Table(name = "ai_projects")
public class AiProjectEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 128)
    private String owner;

    @Column(nullable = false, length = 160)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AiProjectStatus status = AiProjectStatus.ACTIVE;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Version private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected AiProjectEntity() {}

    public static AiProjectEntity create(String owner, String title, int sortOrder) {
        var entity = new AiProjectEntity();
        entity.owner = owner;
        entity.title = normalizeTitle(title);
        entity.sortOrder = sortOrder;
        return entity;
    }

    public void rename(String title) {
        if (status == AiProjectStatus.ARCHIVED) {
            throw new IllegalStateException("Archived AI project cannot be renamed");
        }
        this.title = normalizeTitle(title);
    }

    public void archive() {
        if (status == AiProjectStatus.ARCHIVED) return;
        status = AiProjectStatus.ARCHIVED;
        archivedAt = Instant.now();
    }

    public void restore() {
        status = AiProjectStatus.ACTIVE;
        archivedAt = null;
    }

    private static String normalizeTitle(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("AI project title is required");
        }
        var normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() > 160) return normalized.substring(0, 160);
        return normalized;
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

    public String getOwner() {
        return owner;
    }

    public String getTitle() {
        return title;
    }

    public AiProjectStatus getStatus() {
        return status;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public int getSortOrder() {
        return sortOrder;
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
