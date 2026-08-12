package com.yubai.blog.dish;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishAssetRepository extends JpaRepository<DishAssetEntity, Long> {
    Optional<DishAssetEntity> findByPublicId(UUID publicId);

    Optional<DishAssetEntity> findByPublicIdAndOwner(UUID publicId, String owner);

    Optional<DishAssetEntity> findByDishId(Long dishId);

    long countByOwnerAndDishIdIsNull(String owner);

    @org.springframework.data.jpa.repository.Query(
            "select coalesce(sum(a.byteSize), 0) from DishAssetEntity a "
                    + "where a.owner = :owner and a.dishId is null")
    long sumStagedBytes(@org.springframework.data.repository.query.Param("owner") String owner);

    @org.springframework.data.jpa.repository.Query(
            value = "select pg_advisory_xact_lock(hashtextextended(:owner, 584942))",
            nativeQuery = true)
    Object lockOwnerQuota(@org.springframework.data.repository.query.Param("owner") String owner);

    List<DishAssetEntity> findByDishIdIsNullAndExpiresAtBefore(Instant now);

    void deleteByDishId(Long dishId);
}
