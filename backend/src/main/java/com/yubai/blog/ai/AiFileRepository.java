package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiFileRepository extends JpaRepository<AiFileEntity, UUID> {
    @Query(
            value = "select pg_advisory_xact_lock(hashtextextended(:owner, 1103))",
            nativeQuery = true)
    void lockOwnerQuota(@Param("owner") String owner);

    Optional<AiFileEntity> findByIdAndOwner(UUID id, String owner);

    List<AiFileEntity> findByOwnerAndStatusNotOrderByCreatedAtDesc(
            String owner, AiFileStatus status);

    long countByOwnerAndStatusNotIn(String owner, List<AiFileStatus> statuses);

    List<AiFileEntity> findByStatusInAndExpiresAtBefore(
            List<AiFileStatus> statuses, java.time.Instant expiresAt);

    @Query(
            "select coalesce(sum(f.sizeBytes), 0) from AiFileEntity f "
                    + "where f.owner = :owner and f.status not in :excluded")
    long sumRetainedBytes(
            @Param("owner") String owner, @Param("excluded") List<AiFileStatus> excluded);
}
