package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProjectRepository extends JpaRepository<AiProjectEntity, Long> {
    List<AiProjectEntity> findByOwnerOrderByStatusAscSortOrderAscUpdatedAtDesc(String owner);

    Optional<AiProjectEntity> findByIdAndOwner(Long id, String owner);

    int countByOwnerAndStatus(String owner, AiProjectStatus status);
}
