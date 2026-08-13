package com.yubai.blog.post;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageRequests;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.config.CacheConfig;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class PostService {
    private static final int MAX_SLUG_LENGTH = 120;

    private final PostRepository repository;
    private final PostContentSanitizer sanitizer;
    private final PostRevisionService revisionService;
    private final PostCategoryService categoryService;

    public PostService(
            PostRepository repository,
            PostContentSanitizer sanitizer,
            PostRevisionService revisionService,
            PostCategoryService categoryService) {
        this.repository = repository;
        this.sanitizer = sanitizer;
        this.revisionService = revisionService;
        this.categoryService = categoryService;
    }

    /**
     * P1-2：公开列表返回摘要 DTO（不含正文），并支持分类过滤与时间排序， 供前端 NF-5 服务端真分页使用。
     *
     * @param categorySlug 为空表示不过滤分类
     * @param sort asc=最早优先，其余值一律按最新优先
     */
    public PageResponse<PostSummary> findPublished(
            int page, int size, String categorySlug, String sort) {
        return findPublished(page, size, categorySlug, sort, false);
    }

    /** L-9：featured=true 时按精选标记直查，不再受"首页前 N 条"取窗限制。 */
    public PageResponse<PostSummary> findPublished(
            int page, int size, String categorySlug, String sort, boolean featuredOnly) {
        var pageable = pageRequest(page, size);
        if (featuredOnly) {
            return toSummaryPage(
                    repository.findByFeaturedTrueAndStatusOrderByDateDesc(
                            PostStatus.PUBLISHED, pageable));
        }
        boolean oldestFirst = "asc".equalsIgnoreCase(sort);
        boolean filtered = categorySlug != null && !categorySlug.isBlank();
        var result =
                filtered
                        ? (oldestFirst
                                ? repository.findByCategorySlugAndStatusOrderByDateAsc(
                                        categorySlug, PostStatus.PUBLISHED, pageable)
                                : repository.findByCategorySlugAndStatusOrderByDateDesc(
                                        categorySlug, PostStatus.PUBLISHED, pageable))
                        : (oldestFirst
                                ? repository.findAllByStatusOrderByDateAsc(
                                        PostStatus.PUBLISHED, pageable)
                                : repository.findAllByStatusOrderByDateDesc(
                                        PostStatus.PUBLISHED, pageable));
        return toSummaryPage(result);
    }

    /** P1-2：管理端列表同样只出摘要，编辑时经 findOne 拉取全文。 */
    public PageResponse<PostSummary> findAdmin(PostStatus status, int page, int size) {
        var pageable = pageRequest(page, size);
        var result =
                status == null
                        ? repository.findAllByOrderByDateDesc(pageable)
                        : repository.findAllByStatusOrderByDateDesc(status, pageable);
        return toSummaryPage(result);
    }

    /** L-12：投影分页行 + 一次 IN 批量补标签，列表路径全程不读正文列。 */
    private PageResponse<PostSummary> toSummaryPage(Page<PostRepository.PostListRow> page) {
        var ids = page.stream().map(PostRepository.PostListRow::getId).toList();
        Map<Long, List<String>> tags =
                ids.isEmpty()
                        ? Map.of()
                        : repository.findTagRows(ids).stream()
                                .collect(
                                        Collectors.groupingBy(
                                                row -> (Long) row[0],
                                                Collectors.mapping(
                                                        row -> (String) row[1],
                                                        Collectors.toList())));
        return PageResponse.from(
                page.map(row -> PostSummary.of(row, tags.getOrDefault(row.getId(), List.of()))));
    }

    public PostResponse findPublishedBySlug(String slug) {
        var post =
                repository
                        .findBySlugAndStatus(slug, PostStatus.PUBLISHED)
                        .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        // 3D：相邻导航——(date, id) 元组序的前后各一篇，轻量投影两条 LIMIT 1 查询；
        // 未持久化实体（单测夹具）无 id，跳过邻居查询
        if (post.getId() == null) {
            return PostResponse.from(post);
        }
        var one = PageRequest.of(0, 1);
        var previous =
                firstNeighbor(repository.findPreviousNeighbors(post.getDate(), post.getId(), one));
        var next = firstNeighbor(repository.findNextNeighbors(post.getDate(), post.getId(), one));
        return PostResponse.from(post, previous, next);
    }

    /** 5D：相关推荐——共享标签最多 TOP 4 → 同分类最新 4 篇 → 空列表。结果经 Caffeine 缓存（TTL 5 分钟）。 */
    @Cacheable(cacheNames = CacheConfig.RELATED_POSTS, key = "#postId")
    public List<PostSummary> findRelatedPosts(
            Long postId, List<String> postTags, String postCategory, int limit) {
        var pageable = PageRequest.of(0, limit);
        List<Long> ids = null;

        if (postTags != null && !postTags.isEmpty()) {
            ids = repository.findRelatedPostIdsByTagMatch(postId, postTags, pageable);
        }

        if (ids == null || ids.isEmpty()) {
            if (postCategory == null || postCategory.isBlank()) return List.of();
            var slug = CategorySlug.fromName(postCategory);
            var page =
                    repository.findByCategorySlugAndStatusAndIdNotOrderByDateDesc(
                            slug, PostStatus.PUBLISHED, postId, pageable);
            var rows = page.getContent();
            if (rows.isEmpty()) return List.of();
            return toRelatedSummary(rows);
        }

        var rows = new java.util.ArrayList<>(repository.findRowsByIds(ids));
        var order = new HashMap<Long, Integer>();
        for (int i = 0; i < ids.size(); i++) order.put(ids.get(i), i);
        rows.sort(Comparator.comparing(r -> order.getOrDefault(r.getId(), Integer.MAX_VALUE)));
        return toRelatedSummary(rows);
    }

    /** 5D：轻量投影行 + 一次 IN 批量补标签，列表路径全程不读正文列。 */
    private List<PostSummary> toRelatedSummary(List<PostRepository.PostListRow> rows) {
        var ids = rows.stream().map(PostRepository.PostListRow::getId).toList();
        Map<Long, List<String>> tags =
                ids.isEmpty()
                        ? Map.of()
                        : repository.findTagRows(ids).stream()
                                .collect(
                                        Collectors.groupingBy(
                                                row -> (Long) row[0],
                                                Collectors.mapping(
                                                        row -> (String) row[1],
                                                        Collectors.toList())));
        return rows.stream()
                .map(row -> PostSummary.of(row, tags.getOrDefault(row.getId(), List.of())))
                .toList();
    }

    private static PostResponse.PostNeighbor firstNeighbor(
            List<PostRepository.PostNeighborRow> rows) {
        if (rows == null || rows.isEmpty()) return null;
        var row = rows.get(0);
        return new PostResponse.PostNeighbor(row.getSlug(), row.getTitle());
    }

    @Transactional
    public PostLikeResponse likePost(String slug) {
        // P0-4：改为数据库端原子 UPDATE，避免并发读-改-写丢失计数
        if (repository.incrementLikeCount(slug) == 0) {
            throw new NotFoundException("文章不存在：" + slug);
        }
        var post =
                repository
                        .findBySlugAndStatus(slug, PostStatus.PUBLISHED)
                        .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostLikeResponse.from(post);
    }

    /** P1-8：详情读带来的真实浏览计数；未命中（不存在/未发布）静默为 0，不影响详情读取流程。 */
    @Transactional
    public int registerView(String slug) {
        return repository.incrementViewsCount(slug);
    }

    public PostStatsResponse getStats(String slug) {
        var post =
                repository
                        .findBySlugAndStatus(slug, PostStatus.PUBLISHED)
                        .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostStatsResponse.from(post);
    }

    public PostResponse findOne(long id) {
        return PostResponse.from(entity(id));
    }

    public PostPublicationChecks.Result checkPublication(long id, Instant scheduledAt) {
        return PostPublicationChecks.evaluate(entity(id), scheduledAt);
    }

    public List<String> findPublishedCategories() {
        return repository.findDistinctPublishedCategories();
    }

    public List<CategorySummary> findCategorySummaries() {
        var projections = repository.findPublishedCategoriesWithCount();
        return projections.stream()
                .map(
                        p ->
                                new CategorySummary(
                                        p.getCategory(), p.getCategorySlug(), null, p.getCnt()))
                .toList();
    }

    /** 5B：标签聚合（已发布文章，按数量降序）。 */
    public List<TagSummary> findPublishedTags() {
        return repository.findPublishedTagCounts().stream()
                .map(row -> new TagSummary(row.getTag(), row.getCnt()))
                .toList();
    }

    /** 5B：按标签分页（lower 等值匹配，V25 函数索引）；标签下无文章按 404 语义处理。 */
    public PageResponse<PostSummary> findPublishedByTag(String tag, int page, int size) {
        var result = repository.findPublishedByTag(tag, pageRequest(page, size));
        if (result.getTotalElements() == 0) {
            throw new NotFoundException("标签不存在：" + tag);
        }
        return toSummaryPage(result);
    }

    public record TagSummary(String tag, long count) {}

    public CategoryDetail findCategoryBySlug(String slug, int page, int size) {
        var pageable = pageRequest(page, size);
        var postsPage =
                repository.findByCategorySlugAndStatusOrderByDateDesc(
                        slug, PostStatus.PUBLISHED, pageable);
        if (postsPage.getTotalElements() == 0) {
            throw new NotFoundException("分类不存在：" + slug);
        }
        String categoryName =
                postsPage.getContent().isEmpty()
                        ? slug
                        : postsPage.getContent().get(0).getCategory();
        return CategoryDetail.from(categoryName, slug, null, toSummaryPage(postsPage));
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public PostResponse create(PostRequest request) {
        categoryService.requireExisting(request.category());
        var slug = normalizedSlug(request.slug());
        if (slug == null) {
            slug = generateUniqueSlug(request.title());
        } else {
            requireUniqueSlug(slug, null);
        }
        return PostResponse.from(repository.save(PostEntity.create(request, slug, sanitizer)));
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public PostResponse update(long id, PostRequest request) {
        var post = entity(id);
        if (request.version() != null && request.version() != post.getVersion()) {
            throw new PostVersionConflictException(
                    id, request.version(), post.getVersion(), PostResponse.from(post));
        }
        categoryService.requireExisting(request.category());
        var slug = normalizedSlug(request.slug());
        if (slug == null) {
            slug = post.getSlug();
        } else {
            requireUniqueSlug(slug, id);
        }
        post.update(request, slug, sanitizer);
        if (post.getStatus() == PostStatus.PUBLISHED || post.getScheduledPublishAt() != null) {
            PostPublicationChecks.requirePublishable(post, post.getScheduledPublishAt());
        }
        repository.flush();
        return PostResponse.from(post);
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public PostResponse createWithRevision(PostRequest request) {
        var response = create(request);
        revisionService.record(response.id());
        return response;
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public PostResponse updateWithRevision(long id, PostRequest request) {
        var response = update(id, request);
        revisionService.record(id);
        return response;
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("文章不存在：" + id);
        }
        repository.deleteById(id);
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequests.of(page, size);
    }

    private PostEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("文章不存在：" + id));
    }

    private void requireUniqueSlug(String slug, Long id) {
        boolean exists =
                id == null
                        ? repository.existsBySlug(slug)
                        : repository.existsBySlugAndIdNot(slug, id);
        if (exists) throw new DataIntegrityViolationException("文章 Slug 已存在");
    }

    private String generateUniqueSlug(String title) {
        var base = slugFromTitle(title);
        var candidate = base;
        for (int suffix = 2; repository.existsBySlug(candidate); suffix++) {
            var tail = "-" + suffix;
            var prefix =
                    base.substring(0, Math.min(base.length(), MAX_SLUG_LENGTH - tail.length()))
                            .replaceFirst("-+$", "");
            candidate = prefix + tail;
        }
        return candidate;
    }

    private static String normalizedSlug(String slug) {
        if (slug == null || slug.isBlank()) return null;
        return slug.trim();
    }

    private static String slugFromTitle(String title) {
        var normalized =
                Normalizer.normalize(title, Normalizer.Form.NFKD)
                        .toLowerCase(java.util.Locale.ROOT);
        var slug = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) slug = "post-" + shortHash(title);
        if (slug.length() > MAX_SLUG_LENGTH) {
            slug = slug.substring(0, MAX_SLUG_LENGTH).replaceFirst("-+$", "");
        }
        return slug;
    }

    private static String shortHash(String value) {
        try {
            var digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
