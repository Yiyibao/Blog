package com.yubai.blog.graph;

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
@Table(name = "graph_relations")
public class GraphRelationEntity {
    @Id private UUID id;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "target_id", nullable = false, length = 128)
    private String targetId;

    @Column(name = "relation_type", nullable = false, length = 64)
    private String relationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GraphRelationOrigin origin;

    @Column(name = "created_by", nullable = false, length = 128)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected GraphRelationEntity() {}

    static GraphRelationEntity create(
            String sourceId,
            String targetId,
            String relationType,
            GraphRelationOrigin origin,
            String createdBy) {
        var entity = new GraphRelationEntity();
        entity.id = UUID.randomUUID();
        entity.sourceId = sourceId;
        entity.targetId = targetId;
        entity.relationType = relationType;
        entity.origin = origin;
        entity.createdBy = createdBy;
        return entity;
    }

    void update(String sourceId, String targetId, String relationType) {
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.relationType = relationType;
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

    public String getSourceId() {
        return sourceId;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getRelationType() {
        return relationType;
    }

    public GraphRelationOrigin getOrigin() {
        return origin;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
