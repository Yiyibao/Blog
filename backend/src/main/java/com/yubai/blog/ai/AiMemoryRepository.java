package com.yubai.blog.ai;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiMemoryRepository extends JpaRepository<AiMemoryEntity, UUID> {
    Optional<AiMemoryEntity> findByIdAndOwner(UUID id, String owner);

    List<AiMemoryEntity> findByOwnerAndStatusNotOrderByUpdatedAtDesc(
            String owner, AiMemoryStatus status);

    List<AiMemoryEntity> findByOwnerAndStatusAndExpiresAtIsNullOrderByUpdatedAtDesc(
            String owner, AiMemoryStatus status);

    List<AiMemoryEntity> findByOwnerAndStatusAndExpiresAtAfterOrderByUpdatedAtDesc(
            String owner, AiMemoryStatus status, Instant now);

    @Query(
            """
            select memory from AiMemoryEntity memory
            where memory.owner = :owner
              and memory.status = :status
              and (memory.expiresAt is null or memory.expiresAt > :now)
            order by memory.updatedAt desc
            """)
    List<AiMemoryEntity> findActiveForContext(
            @Param("owner") String owner,
            @Param("status") AiMemoryStatus status,
            @Param("now") Instant now,
            Pageable pageable);
}
