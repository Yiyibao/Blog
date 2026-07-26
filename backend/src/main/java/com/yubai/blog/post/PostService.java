package com.yubai.blog.post;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.PageRequests;

@Service
@Transactional(readOnly = true)
public class PostService {
    private final PostRepository repository;
    private final PostContentSanitizer sanitizer;

    public PostService(PostRepository repository, PostContentSanitizer sanitizer) {
        this.repository = repository;
        this.sanitizer = sanitizer;
    }

    public PageResponse<PostResponse> findPublished(int page, int size) {
        var pageable = pageRequest(page, size);
        return PageResponse.from(repository.findAllByStatusOrderByDateDesc(PostStatus.PUBLISHED, pageable)
            .map(post -> PostResponse.from(post, sanitizer)));
    }

    public PageResponse<PostResponse> findAdmin(PostStatus status, int page, int size) {
        var pageable = pageRequest(page, size);
        var result = status == null
            ? repository.findAllByOrderByDateDesc(pageable)
            : repository.findAllByStatusOrderByDateDesc(status, pageable);
        return PageResponse.from(result.map(post -> PostResponse.from(post, sanitizer)));
    }

    public PostResponse findPublishedBySlug(String slug) {
        var post = repository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostResponse.from(post, sanitizer);
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

    public PostStatsResponse getStats(String slug) {
        var post = repository.findBySlugAndStatus(slug, PostStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
        return PostStatsResponse.from(post);
    }

    public PostResponse findOne(long id) {
        return PostResponse.from(entity(id), sanitizer);
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
        var pageResponse = PageResponse.from(postsPage.map(PostSummary::from));
        return CategoryDetail.from(categoryName, slug, null, pageResponse);
    }

    @Transactional
    public PostResponse create(PostRequest request) {
        requireUniqueSlug(request.slug(), null);
        return PostResponse.from(repository.save(PostEntity.create(request, sanitizer)), sanitizer);
    }

    @Transactional
    public PostResponse update(long id, PostRequest request) {
        var post = entity(id);
        requireUniqueSlug(request.slug(), id);
        post.update(request, sanitizer);
        return PostResponse.from(post, sanitizer);
    }

    @Transactional
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
