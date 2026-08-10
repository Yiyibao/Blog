package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSessionRepository extends JpaRepository<AiSessionEntity, Long> {
    List<AiSessionEntity> findByOwnerOrderByUpdatedAtDesc(String owner);

    Optional<AiSessionEntity> findByIdAndOwner(Long id, String owner);
}
