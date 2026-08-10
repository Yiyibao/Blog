package com.yubai.blog.ai;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTaskRepository extends JpaRepository<AiTaskEntity, UUID> {
    Optional<AiTaskEntity> findByIdAndOwner(UUID id, String owner);

    Optional<AiTaskEntity> findByOwnerAndIdempotencyKey(String owner, String idempotencyKey);

    List<AiTaskEntity> findByOwnerOrderByCreatedAtDesc(String owner);

    List<AiTaskEntity> findByStatusInAndUpdatedAtBefore(
            List<AiTaskStatus> statuses, Instant cutoff);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select task from AiTaskEntity task where task.id = :id")
    Optional<AiTaskEntity> lockById(@Param("id") UUID id);
}
