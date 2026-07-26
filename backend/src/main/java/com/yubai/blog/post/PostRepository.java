package com.yubai.blog.post;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PostRepository extends JpaRepository<PostEntity, Long> {

    interface PostSitemapProjection {
        String getSlug();
        LocalDate getDate();
    }

    public interface CategoryCountProjection {
        String getCategory();
        String getCategorySlug();
        long getCnt();
    }

    @Query("SELECT p.slug as slug, p.date as date FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostSitemapProjection> findPublishedSitemap();

    Page<PostEntity> findAllByOrderByDateDesc(Pageable pageable);

    Page<PostEntity> findAllByStatusOrderByDateDesc(PostStatus status, Pageable pageable);

    /** P1-2：公开列表「最早优先」排序。 */
    Page<PostEntity> findAllByStatusOrderByDateAsc(PostStatus status, Pageable pageable);

    Optional<PostEntity> findBySlugAndStatus(String slug, PostStatus status);

    Optional<PostEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, long id);

    @Query("SELECT p FROM PostEntity p LEFT JOIN FETCH p.tags WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostEntity> findAllPublishedWithTags();

    /** P0-4：数据库端原子自增，消除读-改-写并发丢失更新。 */
    @Modifying
    @Query("UPDATE PostEntity p SET p.likeCount = p.likeCount + 1 WHERE p.slug = :slug AND p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    int incrementLikeCount(@Param("slug") String slug);

    @Query("select distinct p.category from PostEntity p where p.status = com.yubai.blog.post.PostStatus.PUBLISHED order by p.category")
    List<String> findDistinctPublishedCategories();

    @Query("""
        SELECT p.category as category, p.categorySlug as categorySlug, COUNT(p) as cnt
        FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
        GROUP BY p.category, p.categorySlug
        ORDER BY COUNT(p) DESC, p.category ASC
        """)
    List<CategoryCountProjection> findPublishedCategoriesWithCount();

    Page<PostEntity> findByCategoryAndStatusOrderByDateDesc(String category, PostStatus status, Pageable pageable);

    Page<PostEntity> findByCategorySlugAndStatusOrderByDateDesc(String categorySlug, PostStatus status, Pageable pageable);

    /** P1-2：分类过滤 + 最早优先。 */
    Page<PostEntity> findByCategorySlugAndStatusOrderByDateAsc(String categorySlug, PostStatus status, Pageable pageable);

    long countByCategoryAndStatus(String category, PostStatus status);

    long countByCategorySlugAndStatus(String categorySlug, PostStatus status);

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
