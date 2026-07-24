package com.yubai.blog.dish;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("SELECT d.slug as slug, d.updatedAt as updatedAt FROM DishEntity d WHERE d.published = true")
    List<DishSitemapProjection> findPublishedSitemap();
    @Query("SELECT d FROM DishEntity d WHERE d.published = true ORDER BY d.featured DESC, d.displayOrder ASC")
    List<DishEntity> findAllPublishedForGraph();

    Page<DishEntity> findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(Pageable pageable);
    Page<DishEntity> findAllByOrderByDisplayOrderAsc(Pageable pageable);
    Optional<DishEntity> findBySlugAndPublishedTrue(String slug);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, long id);

    Optional<DishEntity> findBySlug(String slug);

    Page<DishEntity> findAllByPublishedTrueOrderByFavoriteCountDesc(Pageable pageable);

    @Query(value = """
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
        """, countQuery = """
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
    Page<DishEntity> searchPublished(@Param("query") String query, Pageable pageable);
}
