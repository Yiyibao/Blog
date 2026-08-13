package com.yubai.blog.post;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PostPreviewTokenRepository extends JpaRepository<PostPreviewTokenEntity, UUID> {
    Optional<PostPreviewTokenEntity> findByTokenHash(String tokenHash);

    Optional<PostPreviewTokenEntity> findByIdAndPostId(UUID id, long postId);
}
