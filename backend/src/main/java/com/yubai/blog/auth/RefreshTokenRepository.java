package com.yubai.blog.auth;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenRepository extends JpaRepository<RefreshTokenEntity, Long> {
    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    List<RefreshTokenEntity> findByFamily(UUID family);

    List<RefreshTokenEntity> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Modifying
    @Query("update RefreshTokenEntity r set r.revoked = true where r.family = :family")
    int revokeFamily(@Param("family") UUID family);

    @Modifying
    @Query("update RefreshTokenEntity r set r.revoked = true, r.lastUsedAt = :now where r.id = :id and r.revoked = false")
    int atomicRevokeAndMarkUsed(@Param("id") Long id, @Param("now") java.time.Instant now);

    @Modifying
    @Query("update RefreshTokenEntity r set r.revoked = true where r.userId = :userId")
    int revokeByUserId(@Param("userId") Long userId);
}
