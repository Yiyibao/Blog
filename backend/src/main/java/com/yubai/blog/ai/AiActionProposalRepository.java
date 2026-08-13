package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiActionProposalRepository extends JpaRepository<AiActionProposalEntity, UUID> {
    Optional<AiActionProposalEntity> findByIdAndOwner(UUID id, String owner);

    List<AiActionProposalEntity> findByOwnerOrderByCreatedAtDesc(String owner);

    List<AiActionProposalEntity> findByOwnerAndStatusOrderByCreatedAtDesc(
            String owner, AiActionProposalStatus status);
}
