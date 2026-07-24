package com.yubai.blog.post;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<PostEntity, Long> {

    interface PostSitemapProjection {
        String getSlug();
        LocalDate getDate();
    }

    @Query("SELECT p.slug as slug, p.date as date FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostSitemapProjection> findPublishedSitemap();
    Page<PostEntity> findAllByOrderByDateDesc(Pageable pageable);

    Page<PostEntity> findAllByStatusOrderByDateDesc(PostStatus status, Pageable pageable);

    Optional<PostEntity> findBySlugAndStatus(String slug, PostStatus status);

    Optional<PostEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, long id);

    @Query("select distinct p.category from PostEntity p where p.status = com.yubai.blog.post.PostStatus.PUBLISHED order by p.category")
    List<String> findDistinctPublishedCategories();

    @Query(value = """
        SELECT DISTINCT p FROM PostEntity p
        LEFT JOIN p.tags tag
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(tag) LIKE LOWER(:query))
        ORDER BY p.date DESC
        """, countQuery = """
        SELECT COUNT(DISTINCT p) FROM PostEntity p
        LEFT JOIN p.tags tag
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(tag) LIKE LOWER(:query))
        """)
    Page<PostEntity> searchPublished(@Param("query") String query, Pageable pageable);
}
