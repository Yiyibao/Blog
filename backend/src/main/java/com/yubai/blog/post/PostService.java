package com.yubai.blog.post;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.PageRequests;
import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class PostService {
    private final PostRepository repository;
    private final PostContentSanitizer sanitizer;

    public PostService(PostRepository repository, PostContentSanitizer sanitizer) {
        this.repository = repository;
        this.sanitizer = sanitizer;
    }

    /**
     * P1-2：公开列表返回摘要 DTO（不含正文），并支持分类过滤与时间排序，
     * 供前端 NF-5 服务端真分页使用。
     *
     * @param categorySlug 为空表示不过滤分类
     * @param sort         asc=最早优先，其余值一律按最新优先
     */
    public PageResponse<PostSummary> findPublished(int page, int size, String categorySlug, String sort) {
        var pageable = pageRequest(page, size);
        boolean oldestFirst = "asc".equalsIgnoreCase(sort);
        boolean filtered = categorySlug != null && !categorySlug.isBlank();
        var result = filtered
            ? (oldestFirst
                ? repository.findByCategorySlugAndStatusOrderByDateAsc(categorySlug, PostStatus.PUBLISHED, pageable)
                : repository.findByCategorySlugAndStatusOrderByDateDesc(categorySlug, PostStatus.PUBLISHED, pageable))
            : (oldestFirst
                ? repository.findAllByStatusOrderByDateAsc(PostStatus.PUBLISHED, pageable)
                : repository.findAllByStatusOrderByDateDesc(PostStatus.PUBLISHED, pageable));
        return toSummaryPage(result);
    }

    /** P1-2：管理端列表同样只出摘要，编辑时经 findOne 拉取全文。 */
    public PageResponse<PostSummary> findAdmin(PostStatus status, int page, int size) {
        var pageable = pageRequest(page, size);
        var result = status == null
            ? repository.findAllByOrderByDateDesc(pageable)
            : repository.findAllByStatusOrderByDateDesc(status, pageable);
        return toSummaryPage(result);
    }

    /** L-12：投影分页行 + 一次 IN 批量补标签，列表路径全程不读正文列。 */
    private PageResponse<PostSummary> toSummaryPage(Page<PostRepository.PostListRow> page) {
        var ids = page.stream().map(PostRepository.PostListRow::getId).toList();
        Map<Long, List<String>> tags = ids.isEmpty() ? Map.of() : repository.findTagRows(ids).stream()
            .collect(Collectors.groupingBy(row -> (Long) row[0],
                Collectors.mapping(row -> (String) row[1], Collectors.toList())));
        return PageResponse.from(page.map(row -> PostSummary.of(row, tags.getOrDefault(row.getId(), List.of()))));
    }

    public PostResponse findPublishedBySlug(String slug) {
        var post = repository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostResponse.from(post);
    }

    @Transactional
    public PostLikeResponse likePost(String slug) {
        // P0-4：改为数据库端原子 UPDATE，避免并发读-改-写丢失计数
        if (repository.incrementLikeCount(slug) == 0) {
            throw new NotFoundException("文章不存在：" + slug);
        }
        var post = repository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostLikeResponse.from(post);
    }

    /** P1-8：详情读带来的真实浏览计数；未命中（不存在/未发布）静默为 0，不影响详情读取流程。 */
    @Transactional
    public int registerView(String slug) {
        return repository.incrementViewsCount(slug);
    }

    public PostStatsResponse getStats(String slug) {
        var post = repository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostStatsResponse.from(post);
    }

    public PostResponse findOne(long id) {
        return PostResponse.from(entity(id));
    }

    public List<String> findPublishedCategories() {
        return repository.findDistinctPublishedCategories();
    }

    public List<CategorySummary> findCategorySummaries() {
        var projections = repository.findPublishedCategoriesWithCount();
        return projections.stream()
            .map(p -> new CategorySummary(p.getCategory(), p.getCategorySlug(), null, p.getCnt()))
            .toList();
    }

    public CategoryDetail findCategoryBySlug(String slug, int page, int size) {
        var pageable = pageRequest(page, size);
        var postsPage = repository.findByCategorySlugAndStatusOrderByDateDesc(slug, PostStatus.PUBLISHED, pageable);
        if (postsPage.getTotalElements() == 0) {
            throw new NotFoundException("分类不存在：" + slug);
        }
        String categoryName = postsPage.getContent().isEmpty() ? slug : postsPage.getContent().get(0).getCategory();
        return CategoryDetail.from(categoryName, slug, null, toSummaryPage(postsPage));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public PostResponse create(PostRequest request) {
        requireUniqueSlug(request.slug(), null);
        return PostResponse.from(repository.save(PostEntity.create(request, sanitizer)));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public PostResponse update(long id, PostRequest request) {
        var post = entity(id);
        requireUniqueSlug(request.slug(), id);
        post.update(request, sanitizer);
        return PostResponse.from(post);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
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
        boolean exists = id == null ? repository.existsBySlug(slug) : repository.existsBySlugAndIdNot(slug, id);
        if (exists) throw new DataIntegrityViolationException("文章 Slug 已存在");
    }
}
