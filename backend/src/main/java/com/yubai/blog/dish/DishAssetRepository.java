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

    interface MediaRow {
        Long getId();

        UUID getPublicId();

        Long getDishId();

        String getOwner();

        String getStorageKey();

        String getFileName();

        String getMediaType();

        long getByteSize();

        String getSha256();

        String getAltText();

        String getSourceUrl();

        String getLicense();

        int getReferenceCount();

        String getCreatedBy();

        Instant getCreatedAt();

        Instant getExpiresAt();
    }

    @org.springframework.data.jpa.repository.Query(
            """
        SELECT a.id as id, a.publicId as publicId, a.dishId as dishId, a.owner as owner,
               a.storageKey as storageKey, a.fileName as fileName, a.mediaType as mediaType,
               a.byteSize as byteSize, a.sha256 as sha256, a.altText as altText,
               a.sourceUrl as sourceUrl, a.license as license, a.referenceCount as referenceCount,
               a.createdBy as createdBy, a.createdAt as createdAt, a.expiresAt as expiresAt
        FROM DishAssetEntity a ORDER BY a.createdAt DESC, a.id DESC
        """)
    List<MediaRow> findMediaRows();
}
