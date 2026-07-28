package com.yubai.blog.dish;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DishImportStagingRepository extends JpaRepository<DishImportStagingEntity, Long> {
    Optional<DishImportStagingEntity> findByToken(UUID token);

    @Modifying
    @Query("UPDATE DishImportStagingEntity s SET s.consumed = true WHERE s.token = :token AND s.consumed = false")
    int markConsumed(@Param("token") UUID token);

    @Modifying
    @Query("delete from DishImportStagingEntity s where s.expiresAt < :now")
    int deleteExpiredBefore(Instant now);

    void deleteByToken(UUID token);
}
