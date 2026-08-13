package com.yubai.blog.graph;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GraphRelationRepository extends JpaRepository<GraphRelationEntity, UUID> {
    List<GraphRelationEntity> findAllByOrderByCreatedAtAscIdAsc();

    List<GraphRelationEntity> findBySourceIdOrderByCreatedAtAscIdAsc(String sourceId);

    List<GraphRelationEntity> findByTargetIdOrderByCreatedAtAscIdAsc(String targetId);

    List<GraphRelationEntity> findBySourceIdOrTargetIdOrderByCreatedAtAscIdAsc(
            String sourceId, String targetId);

    Optional<GraphRelationEntity> findBySourceIdAndTargetIdAndRelationType(
            String sourceId, String targetId, String relationType);
}
