package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTaskEventRepository extends JpaRepository<AiTaskEventEntity, Long> {
    List<AiTaskEventEntity> findByTaskIdAndSequenceGreaterThanOrderBySequenceAsc(
            UUID taskId, long sequence);

    Optional<AiTaskEventEntity> findFirstByTaskIdOrderBySequenceDesc(UUID taskId);
}
