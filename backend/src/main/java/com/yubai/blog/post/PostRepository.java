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

    /** L-12：列表专用轻量行——闭合接口投影使 Hibernate 只 SELECT 这些列，正文列不再随分页读出。 */
    interface PostListRow {
        Long getId();
        String getSlug();
        String getTitle();
        String getExcerpt();
        LocalDate getDate();
        int getReadTime();
        String getCategory();
        String getCategorySlug();
        String getColor();
        String getNumber();
        boolean getFeatured();
        PostStatus getStatus();
        int getLikeCount();
        int getViewsCount();
    }

    /** NB-5：图谱只需 id/标题/slug/分类。 */
    interface PostGraphRow {
        Long getId();
        String getTitle();
        String getSlug();
        String getCategory();
    }

    /** NB-5：搜索命中只取展示所需列，不再为拼 URL/摘要捞整实体（含全文）。L-8：补 date/readTime。 */
    interface PostSearchRow {
        Long getId();
        String getTitle();
        String getExcerpt();
        String getCategory();
        String getSlug();
        String getColor();
        String getNumber();
        LocalDate getDate();
        int getReadTime();
    }

    public interface CategoryCountProjection {
        String getCategory();
        String getCategorySlug();
        long getCnt();
    }

    @Query("SELECT p.slug as slug, p.date as date FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostSitemapProjection> findPublishedSitemap();

    Page<PostListRow> findAllByOrderByDateDesc(Pageable pageable);

    Page<PostListRow> findAllByStatusOrderByDateDesc(PostStatus status, Pageable pageable);

    /** P1-2：公开列表「最早优先」排序。 */
    Page<PostListRow> findAllByStatusOrderByDateAsc(PostStatus status, Pageable pageable);

    Optional<PostEntity> findBySlugAndStatus(String slug, PostStatus status);

    Optional<PostEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, long id);

    /** L-12：给一页文章批量补标签（一次 IN 查询），行结构 [postId, tag]。 */
    @Query("SELECT p.id, t FROM PostEntity p JOIN p.tags t WHERE p.id IN :ids ORDER BY p.id")
    List<Object[]> findTagRows(@Param("ids") java.util.Collection<Long> ids);

    /** NB-5：图谱节点行（不载正文）。 */
    @Query("SELECT p.id as id, p.title as title, p.slug as slug, p.category as category FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostGraphRow> findPublishedGraphRows();

    /** NB-5：图谱标签边（[postId, tag]，仅已发布）。 */
    @Query("SELECT p.id, t FROM PostEntity p JOIN p.tags t WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED ORDER BY p.id")
    List<Object[]> findPublishedTagRows();

    /** P0-4：数据库端原子自增，消除读-改-写并发丢失更新。 */
    @Modifying
    @Query("UPDATE PostEntity p SET p.likeCount = p.likeCount + 1 WHERE p.slug = :slug AND p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    int incrementLikeCount(@Param("slug") String slug);

    /** P1-8：浏览量同样走数据库端原子自增（IP+slug 短窗去重在 Controller 层，不落 IP 明文）。 */
    @Modifying
    @Query("UPDATE PostEntity p SET p.viewsCount = p.viewsCount + 1 WHERE p.slug = :slug AND p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    int incrementViewsCount(@Param("slug") String slug);

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

    Page<PostListRow> findByCategoryAndStatusOrderByDateDesc(String category, PostStatus status, Pageable pageable);

    Page<PostListRow> findByCategorySlugAndStatusOrderByDateDesc(String categorySlug, PostStatus status, Pageable pageable);

    /** P1-2：分类过滤 + 最早优先。 */
    Page<PostListRow> findByCategorySlugAndStatusOrderByDateAsc(String categorySlug, PostStatus status, Pageable pageable);

    long countByCategoryAndStatus(String category, PostStatus status);

    long countByCategorySlugAndStatus(String categorySlug, PostStatus status);

    /**
     * L-8：categorySlug 可选过滤（null 即不过滤）；ORDER BY 不再内嵌，
     * 由调用方经 Pageable.Sort 指定（date DESC/ASC），DISTINCT 所需列均在 SELECT 中。
     */
    @Query(value = """
        SELECT DISTINCT p.id as id, p.title as title, p.excerpt as excerpt, p.category as category,
               p.slug as slug, p.color as color, p.number as number, p.date as date, p.readTime as readTime
        FROM PostEntity p
        LEFT JOIN p.tags tag
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (:categorySlug IS NULL OR p.categorySlug = :categorySlug)
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(p.markdownContent) LIKE LOWER(:query)
            OR LOWER(tag) LIKE LOWER(:query))
        """, countQuery = """
        SELECT COUNT(DISTINCT p) FROM PostEntity p
        LEFT JOIN p.tags tag
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (:categorySlug IS NULL OR p.categorySlug = :categorySlug)
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(p.markdownContent) LIKE LOWER(:query)
            OR LOWER(tag) LIKE LOWER(:query))
        """)
    Page<PostSearchRow> searchPublished(@Param("query") String query,
                                        @Param("categorySlug") String categorySlug,
                                        Pageable pageable);

    /** L-9：精选文章不再受首页取窗限制，直接按标记检索。 */
    Page<PostListRow> findByFeaturedTrueAndStatusOrderByDateDesc(PostStatus status, Pageable pageable);

    /** 3D：相邻文章导航——按 (date, id) 元组序取前一篇/后一篇（轻量投影）。 */
    interface PostNeighborRow {
        String getSlug();
        String getTitle();
    }

    @Query("""
        SELECT p.slug as slug, p.title as title FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (p.date < :date OR (p.date = :date AND p.id < :id))
        ORDER BY p.date DESC, p.id DESC
        """)
    List<PostNeighborRow> findPreviousNeighbors(@Param("date") LocalDate date, @Param("id") long id, Pageable pageable);

    @Query("""
        SELECT p.slug as slug, p.title as title FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (p.date > :date OR (p.date = :date AND p.id > :id))
        ORDER BY p.date ASC, p.id ASC
        """)
    List<PostNeighborRow> findNextNeighbors(@Param("date") LocalDate date, @Param("id") long id, Pageable pageable);
}
