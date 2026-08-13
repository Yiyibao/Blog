package com.yubai.blog.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTaskPartRepository extends JpaRepository<AiTaskPartEntity, Long> {
    List<AiTaskPartEntity> findByTaskIdOrderBySequenceAsc(UUID taskId);

    List<AiTaskPartEntity> findByTaskIdInOrderByCreatedAtAsc(List<UUID> taskIds);

    long countByTaskId(UUID taskId);

    long countByArtifactId(UUID artifactId);

    long countByArtifactIdAndTaskId(UUID artifactId, UUID taskId);

    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query(
            "delete from AiTaskPartEntity p where p.artifactId = :artifactId and p.taskId = :taskId")
    int deleteByArtifactIdAndTaskId(
            @Param("artifactId") UUID artifactId, @Param("taskId") UUID taskId);

    interface ArtifactReferenceCount {
        UUID getArtifactId();

        long getReferenceCount();
    }

    @Query(
            "select p.artifactId as artifactId, count(p) as referenceCount "
                    + "from AiTaskPartEntity p where p.artifactId is not null "
                    + "group by p.artifactId")
    List<ArtifactReferenceCount> countArtifactReferences();

    @Query(
            """
            select part from AiTaskPartEntity part, AiTaskEntity task
            where part.taskId = task.id
              and task.owner = :owner
              and task.sessionId = :sessionId
              and task.status = :status
              and part.kind = :kind
            order by task.createdAt desc, part.sequence desc
            """)
    List<AiTaskPartEntity> findSessionPartsNewestFirst(
            @Param("owner") String owner,
            @Param("sessionId") Long sessionId,
            @Param("status") AiTaskStatus status,
            @Param("kind") AiPartKind kind,
            Pageable pageable);
}
