package com.yubai.blog.post;

import java.util.List;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class PostCategoryService {
    private final PostCategoryRepository repository;
    private final PostRepository postRepository;

    public PostCategoryService(PostCategoryRepository repository, PostRepository postRepository) {
        this.repository = repository;
        this.postRepository = postRepository;
    }

    public List<AdminPostCategory> findAll() {
        return repository.findAllByOrderByNameAsc().stream().map(this::response).toList();
    }

    public void requireExisting(String name) {
        if (repository.findByName(normalizeName(name)).isEmpty()) {
            throw new NotFoundException("文章类别不存在，请先在类别管理中创建");
        }
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP, CacheConfig.RSS, CacheConfig.RELATED_POSTS}, allEntries = true)
    public AdminPostCategory create(PostCategoryRequest request) {
        var name = normalizeName(request.name());
        var slug = CategorySlug.fromName(name);
        requireUnique(name, slug, null);
        return response(repository.save(PostCategoryEntity.create(name, slug, description(request.description()))));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP, CacheConfig.RSS, CacheConfig.RELATED_POSTS}, allEntries = true)
    public AdminPostCategory update(long id, PostCategoryRequest request) {
        var category = entity(id);
        var oldName = category.getName();
        var name = normalizeName(request.name());
        var slug = CategorySlug.fromName(name);
        requireUnique(name, slug, id);
        category.update(name, slug, description(request.description()));
        postRepository.updateCategory(oldName, name, slug);
        return response(category);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP, CacheConfig.RSS, CacheConfig.RELATED_POSTS}, allEntries = true)
    public void delete(long id) {
        var category = entity(id);
        if (postRepository.countByCategory(category.getName()) > 0) {
            throw new DataIntegrityViolationException("类别仍被文章使用");
        }
        repository.delete(category);
    }

    private AdminPostCategory response(PostCategoryEntity category) {
        return new AdminPostCategory(category.getId(), category.getName(), category.getSlug(), category.getDescription(),
            postRepository.countByCategory(category.getName()),
            postRepository.countByCategoryAndStatus(category.getName(), PostStatus.PUBLISHED));
    }

    private PostCategoryEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("文章类别不存在：" + id));
    }

    private void requireUnique(String name, String slug, Long id) {
        boolean exists = id == null
            ? repository.existsByName(name) || repository.existsBySlug(slug)
            : repository.existsByNameAndIdNot(name, id) || repository.existsBySlugAndIdNot(slug, id);
        if (exists) throw new DataIntegrityViolationException("文章类别已存在");
    }

    private static String normalizeName(String name) {
        return name.trim();
    }

    private static String description(String value) {
        return value == null ? "" : value.trim();
    }
}
