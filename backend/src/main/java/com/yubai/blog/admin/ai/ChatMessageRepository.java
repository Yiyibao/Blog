package com.yubai.blog.admin.ai;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {
    List<ChatMessageEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);

    long countBySessionId(Long sessionId);

    void deleteBySessionId(Long sessionId);
}
