package com.yubai.blog.admin.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiGeneratedImageRepository extends JpaRepository<AiGeneratedImageEntity, Long> {
    Optional<AiGeneratedImageEntity> findByPublicId(UUID publicId);

    List<AiGeneratedImageEntity> findBySessionIdOrderByCreatedAtAsc(Long sessionId);
}
