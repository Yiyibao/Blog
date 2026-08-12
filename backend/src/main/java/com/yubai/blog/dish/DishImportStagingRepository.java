package com.yubai.blog.dish;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DishImportStagingRepository extends JpaRepository<DishImportStagingEntity, Long> {
    @Query(
            value = "select pg_advisory_xact_lock(hashtextextended(:owner, 1102))",
            nativeQuery = true)
    void lockOwnerQuota(@Param("owner") String owner);

    Optional<DishImportStagingEntity> findByToken(UUID token);

    Optional<DishImportStagingEntity> findByTokenAndOwner(UUID token, String owner);

    long countByOwnerAndConsumedFalseAndCancelledFalseAndExpiresAtAfter(String owner, Instant now);

    @Query(
            "select coalesce(sum(s.byteSize), 0) from DishImportStagingEntity s "
                    + "where s.owner = :owner and s.consumed = false and s.cancelled = false "
                    + "and s.expiresAt > :now")
    long sumActiveBytes(@Param("owner") String owner, @Param("now") Instant now);

    List<DishImportStagingEntity> findByExpiresAtBefore(Instant now);

    @Modifying
    @Query(
            "UPDATE DishImportStagingEntity s SET s.consumed = true WHERE s.token = :token AND s.consumed = false")
    int markConsumed(@Param("token") UUID token);

    @Modifying
    @Query("delete from DishImportStagingEntity s where s.expiresAt < :now")
    int deleteExpiredBefore(Instant now);

    void deleteByToken(UUID token);
}
