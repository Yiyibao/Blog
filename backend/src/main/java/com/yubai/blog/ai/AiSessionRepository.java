package com.yubai.blog.ai;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSessionRepository extends JpaRepository<AiSessionEntity, Long> {
    List<AiSessionEntity> findByOwnerAndStatusNotOrderByUpdatedAtDesc(
            String owner, AiSessionStatus status);

    List<AiSessionEntity> findByOwnerAndProjectIdAndStatusNotOrderByUpdatedAtDesc(
            String owner, Long projectId, AiSessionStatus status);

    Optional<AiSessionEntity> findByIdAndOwner(Long id, String owner);

    int countByOwnerAndProjectIdAndStatusNot(String owner, Long projectId, AiSessionStatus status);

    default List<AiSessionEntity> findByOwnerOrderByUpdatedAtDesc(String owner) {
        return findByOwnerAndStatusNotOrderByUpdatedAtDesc(owner, AiSessionStatus.DELETED);
    }
}
