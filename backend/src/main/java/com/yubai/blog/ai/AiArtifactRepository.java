package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiArtifactRepository extends JpaRepository<AiArtifactEntity, UUID> {
    @Query(
            value = "select pg_advisory_xact_lock(hashtextextended(:owner, 1104))",
            nativeQuery = true)
    void lockOwnerQuota(@Param("owner") String owner);

    Optional<AiArtifactEntity> findByIdAndOwner(UUID id, String owner);

    Optional<AiArtifactEntity> findByTaskIdAndName(UUID taskId, String name);

    List<AiArtifactEntity> findByOwnerAndStatusNotOrderByCreatedAtDesc(
            String owner, AiArtifactStatus status);

    List<AiArtifactEntity> findByTaskIdAndOwnerOrderByCreatedAtAsc(UUID taskId, String owner);

    List<AiArtifactEntity> findByStatusInAndExpiresAtBefore(
            List<AiArtifactStatus> statuses, java.time.Instant expiresAt);

    long countByOwnerAndStatusNotIn(String owner, List<AiArtifactStatus> statuses);

    @Query(
            "select coalesce(sum(a.sizeBytes), 0) from AiArtifactEntity a "
                    + "where a.owner = :owner and a.status not in :excluded")
    long sumRetainedBytes(
            @Param("owner") String owner, @Param("excluded") List<AiArtifactStatus> excluded);
}
