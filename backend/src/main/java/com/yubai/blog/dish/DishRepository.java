package com.yubai.blog.dish;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DishRepository extends JpaRepository<DishEntity, Long> {

    interface DishFavoriteProjection {
        String getSlug();

        String getName();

        String getSummary();

        String getImageUrl();

        int getFavoriteCount();
    }

    interface DishSitemapProjection {
        String getSlug();

        Instant getUpdatedAt();
    }

    @Query(
            "SELECT d.slug as slug, d.updatedAt as updatedAt FROM DishEntity d WHERE d.published = true")
    List<DishSitemapProjection> findPublishedSitemap();

    /** NB-5：图谱只需 id/名称/slug/分类。 */
    interface DishGraphRow {
        Long getId();

        String getName();

        String getSlug();

        String getCategory();

        String getImageUrl();

        Instant getUpdatedAt();
    }

    /** NB-5：搜索命中投影。 */
    interface DishSearchRow {
        Long getId();

        String getName();

        String getSummary();

        String getCategory();

        String getSlug();

        default java.time.Instant getUpdatedAt() {
            return null;
        }
    }

    @Query(
            "SELECT d.id as id, d.name as name, d.slug as slug, d.category as category, d.imageUrl as imageUrl, d.updatedAt as updatedAt FROM DishEntity d WHERE d.published = true ORDER BY d.featured DESC, d.displayOrder ASC")
    List<DishGraphRow> findAllPublishedForGraph();

    Page<DishEntity> findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(Pageable pageable);

    Page<DishEntity> findByCategoryAndPublishedTrueOrderByFeaturedDescDisplayOrderAsc(
            String category, Pageable pageable);

    Page<DishEntity> findAllByOrderByDisplayOrderAsc(Pageable pageable);

    Optional<DishEntity> findBySlugAndPublishedTrue(String slug);

    boolean existsBySlug(String slug);

    long countByCategory(String category);

    long countByCategoryAndPublishedTrue(String category);

    @Modifying
    @Query("UPDATE DishEntity d SET d.category = :newName WHERE d.category = :oldName")
    int updateCategory(@Param("oldName") String oldName, @Param("newName") String newName);

    Optional<DishEntity> findBySlug(String slug);

    @Query("SELECT COALESCE(MAX(d.displayOrder), 0) FROM DishEntity d")
    int maxDisplayOrder();

    Page<DishEntity> findAllByPublishedTrueOrderByFavoriteCountDesc(Pageable pageable);

    /** P0-4：数据库端原子自增，消除读-改-写并发丢失更新。 */
    @Modifying
    @Query(
            "UPDATE DishEntity d SET d.favoriteCount = d.favoriteCount + 1 WHERE d.slug = :slug AND d.published = true")
    int incrementFavoriteCount(@Param("slug") String slug);

    /** 3C：浏览量同走数据库端原子自增（IP+slug 短窗去重在 Controller 层，不落 IP 明文）。 */
    @Modifying
    @Query(
            "UPDATE DishEntity d SET d.viewsCount = d.viewsCount + 1 WHERE d.slug = :slug AND d.published = true")
    int incrementViewsCount(@Param("slug") String slug);

    @Query(
            value =
                    """
        SELECT DISTINCT d.id as id, d.name as name, d.summary as summary, d.category as category,
               d.slug as slug, d.featured as featured, d.displayOrder as displayOrder
        FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        ORDER BY d.featured DESC, d.displayOrder ASC
        """,
            countQuery =
                    """
        SELECT COUNT(DISTINCT d) FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        """)
    Page<DishSearchRow> searchPublished(@Param("query") String query, Pageable pageable);

    @Query(
            value =
                    """
        SELECT DISTINCT d.id as id, d.name as name, d.summary as summary, d.category as category,
               d.slug as slug, d.featured as featured, d.displayOrder as displayOrder,
               d.updatedAt as updatedAt
        FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND d.updatedAt >= :fromDate AND d.updatedAt < :toDate
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        ORDER BY d.featured DESC, d.displayOrder ASC
        """,
            countQuery =
                    """
        SELECT COUNT(DISTINCT d) FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND d.updatedAt >= :fromDate AND d.updatedAt < :toDate
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        """)
    Page<DishSearchRow> searchPublishedBetween(
            @Param("query") String query,
            @Param("fromDate") java.time.Instant fromDate,
            @Param("toDate") java.time.Instant toDate,
            Pageable pageable);

    @Query(
            value =
                    """
        SELECT DISTINCT d FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        ORDER BY d.featured DESC, d.displayOrder ASC
        """,
            countQuery =
                    """
        SELECT COUNT(DISTINCT d) FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        """)
    Page<DishEntity> searchPublishedEntities(@Param("query") String query, Pageable pageable);

    @Query(
            value =
                    """
        SELECT DISTINCT d FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND d.category = :category
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        ORDER BY d.featured DESC, d.displayOrder ASC
        """,
            countQuery =
                    """
        SELECT COUNT(DISTINCT d) FROM DishEntity d
        LEFT JOIN d.ingredients ingredient
        LEFT JOIN d.steps step
        WHERE d.published = true
          AND d.category = :category
          AND (LOWER(d.name) LIKE LOWER(:query)
            OR LOWER(d.summary) LIKE LOWER(:query)
            OR LOWER(d.category) LIKE LOWER(:query)
            OR LOWER(ingredient) LIKE LOWER(:query)
            OR LOWER(step) LIKE LOWER(:query))
        """)
    Page<DishEntity> searchPublishedByCategory(
            @Param("category") String category, @Param("query") String query, Pageable pageable);
}
