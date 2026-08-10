package com.yubai.blog.ai;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTaskPartRepository extends JpaRepository<AiTaskPartEntity, Long> {
    List<AiTaskPartEntity> findByTaskIdOrderBySequenceAsc(UUID taskId);

    long countByTaskId(UUID taskId);

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
