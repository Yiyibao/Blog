package com.yubai.blog.admin.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiImageSessionRepository extends JpaRepository<AiImageSessionEntity, Long> {
    List<AiImageSessionEntity> findByOwnerOrderByUpdatedAtDesc(String owner);

    Optional<AiImageSessionEntity> findByIdAndOwner(Long id, String owner);
}
