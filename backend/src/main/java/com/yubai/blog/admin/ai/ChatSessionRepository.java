package com.yubai.blog.admin.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, Long> {
    List<ChatSessionEntity> findByOwnerOrderByUpdatedAtDesc(String owner);

    Optional<ChatSessionEntity> findByIdAndOwner(Long id, String owner);
}
