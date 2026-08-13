package com.yubai.blog.graph;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "graph_relation_audits")
public class GraphRelationAuditEntity {
    @Id private UUID id;

    @Column(name = "relation_id")
    private UUID relationId;

    @Column(name = "source_id", nullable = false, length = 128)
    private String sourceId;

    @Column(name = "target_id", nullable = false, length = 128)
    private String targetId;

    @Column(name = "relation_type", nullable = false, length = 64)
    private String relationType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private GraphRelationOrigin origin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private GraphRelationAction action;

    @Column(nullable = false, length = 128)
    private String actor;

    @Column(name = "relation_version", nullable = false)
    private long relationVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected GraphRelationAuditEntity() {}

    static GraphRelationAuditEntity record(
            GraphRelationEntity relation, GraphRelationAction action, String actor, long version) {
        var audit = new GraphRelationAuditEntity();
        audit.id = UUID.randomUUID();
        audit.relationId = relation.getId();
        audit.sourceId = relation.getSourceId();
        audit.targetId = relation.getTargetId();
        audit.relationType = relation.getRelationType();
        audit.origin = relation.getOrigin();
        audit.action = action;
        audit.actor = actor;
        audit.relationVersion = version;
        audit.createdAt = Instant.now();
        return audit;
    }

    static GraphRelationAuditEntity recordDeleted(
            UUID relationId,
            String sourceId,
            String targetId,
            String relationType,
            GraphRelationOrigin origin,
            String actor,
            long version) {
        var audit = new GraphRelationAuditEntity();
        audit.id = UUID.randomUUID();
        audit.relationId = relationId;
        audit.sourceId = sourceId;
        audit.targetId = targetId;
        audit.relationType = relationType;
        audit.origin = origin;
        audit.action = GraphRelationAction.DELETE;
        audit.actor = actor;
        audit.relationVersion = version;
        audit.createdAt = Instant.now();
        return audit;
    }

    public UUID getId() {
        return id;
    }

    public UUID getRelationId() {
        return relationId;
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

    public GraphRelationAction getAction() {
        return action;
    }

    public String getActor() {
        return actor;
    }

    public long getRelationVersion() {
        return relationVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
