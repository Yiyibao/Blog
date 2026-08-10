package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiArtifactRepository extends JpaRepository<AiArtifactEntity, UUID> {
    Optional<AiArtifactEntity> findByIdAndOwner(UUID id, String owner);

    Optional<AiArtifactEntity> findByTaskIdAndName(UUID taskId, String name);

    List<AiArtifactEntity> findByOwnerAndStatusNotOrderByCreatedAtDesc(
            String owner, AiArtifactStatus status);

    List<AiArtifactEntity> findByTaskIdAndOwnerOrderByCreatedAtAsc(UUID taskId, String owner);

    List<AiArtifactEntity> findByStatusInAndExpiresAtBefore(
            List<AiArtifactStatus> statuses, java.time.Instant expiresAt);
}
