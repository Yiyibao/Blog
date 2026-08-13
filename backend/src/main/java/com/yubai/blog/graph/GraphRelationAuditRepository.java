package com.yubai.blog.graph;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GraphRelationAuditRepository
        extends JpaRepository<GraphRelationAuditEntity, UUID> {
    List<GraphRelationAuditEntity> findByRelationIdOrderByCreatedAtDesc(UUID relationId);
}
