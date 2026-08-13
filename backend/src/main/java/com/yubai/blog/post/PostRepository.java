package com.yubai.blog.post;

import java.time.Instant;
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

        Instant getScheduledPublishAt();

        int getLikeCount();

        int getViewsCount();
    }

    /** NB-5：图谱只需 id/标题/slug/分类。 */
    interface PostGraphRow {
        Long getId();

        String getTitle();

        String getSlug();

        String getCategory();

        LocalDate getDate();
    }

    /** NB-5：搜索命中只取展示所需列，不再为拼 URL/摘要捞整实体（含全文）。L-8：补 date/readTime。5A：补加权分。 */
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

        int getScore();
    }

    public interface CategoryCountProjection {
        String getCategory();

        String getCategorySlug();

        long getCnt();
    }

    @Query(
            "SELECT p.slug as slug, p.date as date FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostSitemapProjection> findPublishedSitemap();

    Page<PostListRow> findAllByOrderByDateDesc(Pageable pageable);

    Page<PostListRow> findAllByStatusOrderByDateDesc(PostStatus status, Pageable pageable);

    /** P1-2：公开列表「最早优先」排序。 */
    Page<PostListRow> findAllByStatusOrderByDateAsc(PostStatus status, Pageable pageable);

    Optional<PostEntity> findBySlugAndStatus(String slug, PostStatus status);

    Optional<PostEntity> findBySlug(String slug);

    List<PostEntity> findByStatusAndScheduledPublishAtLessThanEqual(PostStatus status, Instant now);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, long id);

    /** L-12：给一页文章批量补标签（一次 IN 查询），行结构 [postId, tag]。 */
    @Query("SELECT p.id, t FROM PostEntity p JOIN p.tags t WHERE p.id IN :ids ORDER BY p.id")
    List<Object[]> findTagRows(@Param("ids") java.util.Collection<Long> ids);

    /** NB-5：图谱节点行（不载正文）。 */
    @Query(
            "SELECT p.id as id, p.title as title, p.slug as slug, p.category as category, p.date as date FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    List<PostGraphRow> findPublishedGraphRows();

    /** NB-5：图谱标签边（[postId, tag]，仅已发布）。 */
    @Query(
            "SELECT p.id, t FROM PostEntity p JOIN p.tags t WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED ORDER BY p.id")
    List<Object[]> findPublishedTagRows();

    /** P0-4：数据库端原子自增，消除读-改-写并发丢失更新。 */
    @Modifying
    @Query(
            "UPDATE PostEntity p SET p.likeCount = p.likeCount + 1 WHERE p.slug = :slug AND p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    int incrementLikeCount(@Param("slug") String slug);

    /** P1-8：浏览量同样走数据库端原子自增（IP+slug 短窗去重在 Controller 层，不落 IP 明文）。 */
    @Modifying
    @Query(
            "UPDATE PostEntity p SET p.viewsCount = p.viewsCount + 1 WHERE p.slug = :slug AND p.status = com.yubai.blog.post.PostStatus.PUBLISHED")
    int incrementViewsCount(@Param("slug") String slug);

    @Query(
            "select distinct p.category from PostEntity p where p.status = com.yubai.blog.post.PostStatus.PUBLISHED order by p.category")
    List<String> findDistinctPublishedCategories();

    @Query(
            """
        SELECT p.category as category, p.categorySlug as categorySlug, COUNT(p) as cnt
        FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
        GROUP BY p.category, p.categorySlug
        ORDER BY COUNT(p) DESC, p.category ASC
        """)
    List<CategoryCountProjection> findPublishedCategoriesWithCount();

    Page<PostListRow> findByCategoryAndStatusOrderByDateDesc(
            String category, PostStatus status, Pageable pageable);

    Page<PostListRow> findByCategorySlugAndStatusOrderByDateDesc(
            String categorySlug, PostStatus status, Pageable pageable);

    /** P1-2：分类过滤 + 最早优先。 */
    Page<PostListRow> findByCategorySlugAndStatusOrderByDateAsc(
            String categorySlug, PostStatus status, Pageable pageable);

    long countByCategoryAndStatus(String category, PostStatus status);

    long countByCategory(String category);

    long countByCategorySlugAndStatus(String categorySlug, PostStatus status);

    @Modifying
    @Query(
            "UPDATE PostEntity p SET p.category = :newName, p.categorySlug = :newSlug WHERE p.category = :oldName")
    int updateCategory(
            @Param("oldName") String oldName,
            @Param("newName") String newName,
            @Param("newSlug") String newSlug);

    /**
     * L-8：categorySlug 可选过滤（null 即不过滤）；ORDER BY 由调用方经 Pageable.Sort 指定 （5A relevance 用
     * JpaSort.unsafe 引用 score 别名）。 5A：标签条件 EXISTS 化替代 LEFT JOIN+DISTINCT（每篇一行，score 表达式不受 tag
     * 行复制干扰）； 加权分：标题 4 > 摘要/分类/标签 2 > 正文 1（LIKE 召回面即降级兜底，pg_trgm 索引仍服务加速）。
     */
    @Query(
            value =
                    """
        SELECT p.id as id, p.title as title, p.excerpt as excerpt, p.category as category,
               p.slug as slug, p.color as color, p.number as number, p.date as date, p.readTime as readTime,
               (CASE WHEN LOWER(p.title) LIKE LOWER(:query) THEN 4 ELSE 0 END
                + CASE WHEN LOWER(p.excerpt) LIKE LOWER(:query) THEN 2 ELSE 0 END
                + CASE WHEN LOWER(p.category) LIKE LOWER(:query) THEN 2 ELSE 0 END
                + CASE WHEN EXISTS (SELECT 1 FROM PostEntity p2 JOIN p2.tags tag2
                                    WHERE p2.id = p.id AND LOWER(tag2) LIKE LOWER(:query)) THEN 2 ELSE 0 END
                + CASE WHEN LOWER(p.content) LIKE LOWER(:query)
                        OR LOWER(p.markdownContent) LIKE LOWER(:query) THEN 1 ELSE 0 END) as score
        FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (:categorySlug IS NULL OR p.categorySlug = :categorySlug)
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(p.markdownContent) LIKE LOWER(:query)
            OR EXISTS (SELECT 1 FROM PostEntity p3 JOIN p3.tags tag3
                       WHERE p3.id = p.id AND LOWER(tag3) LIKE LOWER(:query)))
        """,
            countQuery =
                    """
        SELECT COUNT(p) FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (:categorySlug IS NULL OR p.categorySlug = :categorySlug)
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(p.markdownContent) LIKE LOWER(:query)
            OR EXISTS (SELECT 1 FROM PostEntity p3 JOIN p3.tags tag3
                       WHERE p3.id = p.id AND LOWER(tag3) LIKE LOWER(:query)))
        """)
    Page<PostSearchRow> searchPublished(
            @Param("query") String query,
            @Param("categorySlug") String categorySlug,
            Pageable pageable);

    /** M9: push tag/date filters into the paged query so counts stay exact. */
    @Query(
            value =
                    """
        SELECT p.id as id, p.title as title, p.excerpt as excerpt, p.category as category,
               p.slug as slug, p.color as color, p.number as number, p.date as date, p.readTime as readTime,
               (CASE WHEN LOWER(p.title) LIKE LOWER(:query) THEN 4 ELSE 0 END
                + CASE WHEN LOWER(p.excerpt) LIKE LOWER(:query) THEN 2 ELSE 0 END
                + CASE WHEN LOWER(p.category) LIKE LOWER(:query) THEN 2 ELSE 0 END
                + CASE WHEN EXISTS (SELECT 1 FROM PostEntity p2 JOIN p2.tags tag2
                                    WHERE p2.id = p.id AND LOWER(tag2) LIKE LOWER(:query)) THEN 2 ELSE 0 END
                + CASE WHEN LOWER(p.content) LIKE LOWER(:query)
                        OR LOWER(p.markdownContent) LIKE LOWER(:query) THEN 1 ELSE 0 END) as score
        FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (:categoryFilter = false OR p.categorySlug = :categorySlug)
          AND (:tagFilter = false OR EXISTS (SELECT 1 FROM PostEntity p4 JOIN p4.tags tag4
                                       WHERE p4.id = p.id AND LOWER(tag4) = LOWER(:tag)))
          AND p.date >= :fromDate
          AND p.date <= :toDate
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(p.markdownContent) LIKE LOWER(:query)
            OR EXISTS (SELECT 1 FROM PostEntity p3 JOIN p3.tags tag3
                       WHERE p3.id = p.id AND LOWER(tag3) LIKE LOWER(:query)))
        """,
            countQuery =
                    """
        SELECT COUNT(p) FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (:categoryFilter = false OR p.categorySlug = :categorySlug)
          AND (:tagFilter = false OR EXISTS (SELECT 1 FROM PostEntity p4 JOIN p4.tags tag4
                                       WHERE p4.id = p.id AND LOWER(tag4) = LOWER(:tag)))
          AND p.date >= :fromDate
          AND p.date <= :toDate
          AND (LOWER(p.title) LIKE LOWER(:query)
            OR LOWER(p.excerpt) LIKE LOWER(:query)
            OR LOWER(p.category) LIKE LOWER(:query)
            OR LOWER(p.content) LIKE LOWER(:query)
            OR LOWER(p.markdownContent) LIKE LOWER(:query)
            OR EXISTS (SELECT 1 FROM PostEntity p3 JOIN p3.tags tag3
                       WHERE p3.id = p.id AND LOWER(tag3) LIKE LOWER(:query)))
        """)
    Page<PostSearchRow> searchPublishedWithFilters(
            @Param("query") String query,
            @Param("categorySlug") String categorySlug,
            @Param("tag") String tag,
            @Param("categoryFilter") boolean categoryFilter,
            @Param("tagFilter") boolean tagFilter,
            @Param("fromDate") java.time.LocalDate fromDate,
            @Param("toDate") java.time.LocalDate toDate,
            Pageable pageable);

    /** L-9：精选文章不再受首页取窗限制，直接按标记检索。 */
    Page<PostListRow> findByFeaturedTrueAndStatusOrderByDateDesc(
            PostStatus status, Pageable pageable);

    /** 4B：合集成员补齐用的轻量引用行（不读正文列）。 */
    interface PostRefRow {
        Long getId();

        String getSlug();

        String getTitle();

        LocalDate getDate();

        PostStatus getStatus();
    }

    @Query(
            "SELECT p.id as id, p.slug as slug, p.title as title, p.date as date, p.status as status FROM PostEntity p WHERE p.id IN :ids")
    List<PostRefRow> findRefRows(@Param("ids") java.util.Collection<Long> ids);

    /** 5B：标签一等公民——已发布文章的标签聚合与按标签分页（lower 等值匹配走 V25 函数索引）。 */
    interface TagCountRow {
        String getTag();

        long getCnt();
    }

    @Query(
            """
        SELECT t as tag, COUNT(p) as cnt FROM PostEntity p JOIN p.tags t
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
        GROUP BY t ORDER BY COUNT(p) DESC, t ASC
        """)
    List<TagCountRow> findPublishedTagCounts();

    @Query(
            value =
                    """
        SELECT p.id as id, p.slug as slug, p.title as title, p.excerpt as excerpt, p.date as date,
               p.readTime as readTime, p.category as category, p.categorySlug as categorySlug,
               p.color as color, p.number as number, p.featured as featured, p.status as status,
               p.likeCount as likeCount, p.viewsCount as viewsCount
        FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND EXISTS (SELECT 1 FROM PostEntity p2 JOIN p2.tags t
                      WHERE p2.id = p.id AND LOWER(t) = LOWER(:tag))
        ORDER BY p.date DESC, p.id DESC
        """,
            countQuery =
                    """
        SELECT COUNT(p) FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND EXISTS (SELECT 1 FROM PostEntity p2 JOIN p2.tags t
                      WHERE p2.id = p.id AND LOWER(t) = LOWER(:tag))
        """)
    Page<PostListRow> findPublishedByTag(@Param("tag") String tag, Pageable pageable);

    /** 4D：仪表盘状态计数与 TOP5 热文（轻量投影）。 */
    long countByStatus(PostStatus status);

    interface TopPostRow {
        String getTitle();

        String getSlug();

        int getViewsCount();

        int getLikeCount();
    }

    @Query(
            """
        SELECT p.title as title, p.slug as slug, p.viewsCount as viewsCount, p.likeCount as likeCount
        FROM PostEntity p WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
        ORDER BY p.viewsCount DESC, p.likeCount DESC, p.id ASC
        """)
    List<TopPostRow> findTopViewed(Pageable pageable);

    /** 5D：共享标签最多 TOP N，排除自身（按共享标签数降序，用于相关推荐）。 */
    @Query(
            """
        SELECT p.id FROM PostEntity p JOIN p.tags t
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND p.id <> :postId
          AND t IN :tags
        GROUP BY p.id
        ORDER BY COUNT(t) DESC
        """)
    List<Long> findRelatedPostIdsByTagMatch(
            @Param("postId") Long postId,
            @Param("tags") java.util.Collection<String> tags,
            Pageable pageable);

    /** 5D：同分类最新 N 篇（排除自身，无共享标签时的退路）。 */
    Page<PostListRow> findByCategorySlugAndStatusAndIdNotOrderByDateDesc(
            String categorySlug, PostStatus status, Long id, Pageable pageable);

    /** 5D：按 ID 批量取轻量投影行（用于相关推荐组装，在外层按 ID 序重排）。 */
    @Query(
            """
        SELECT p.id as id, p.slug as slug, p.title as title, p.excerpt as excerpt,
               p.date as date, p.readTime as readTime, p.category as category, p.categorySlug as categorySlug,
               p.color as color, p.number as number, p.featured as featured, p.status as status,
               p.scheduledPublishAt as scheduledPublishAt,
               p.likeCount as likeCount, p.viewsCount as viewsCount
        FROM PostEntity p WHERE p.id IN :ids
        """)
    List<PostListRow> findRowsByIds(@Param("ids") java.util.Collection<Long> ids);

    /** 3D：相邻文章导航——按 (date, id) 元组序取前一篇/后一篇（轻量投影）。 */
    interface PostNeighborRow {
        String getSlug();

        String getTitle();
    }

    @Query(
            """
        SELECT p.slug as slug, p.title as title FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (p.date < :date OR (p.date = :date AND p.id < :id))
        ORDER BY p.date DESC, p.id DESC
        """)
    List<PostNeighborRow> findPreviousNeighbors(
            @Param("date") LocalDate date, @Param("id") long id, Pageable pageable);

    @Query(
            """
        SELECT p.slug as slug, p.title as title FROM PostEntity p
        WHERE p.status = com.yubai.blog.post.PostStatus.PUBLISHED
          AND (p.date > :date OR (p.date = :date AND p.id > :id))
        ORDER BY p.date ASC, p.id ASC
        """)
    List<PostNeighborRow> findNextNeighbors(
            @Param("date") LocalDate date, @Param("id") long id, Pageable pageable);
}
