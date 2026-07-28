package com.yubai.blog.dish;

import java.util.List;
import java.util.Locale;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class DishCategoryService {
    private final DishCategoryRepository repository;
    private final DishRepository dishRepository;

    public DishCategoryService(DishCategoryRepository repository, DishRepository dishRepository) {
        this.repository = repository;
        this.dishRepository = dishRepository;
    }

    public List<AdminDishCategory> findAll() {
        return repository.findAllByOrderByNameAsc().stream().map(this::response).toList();
    }

    public List<DishCategorySummary> findAllPublic() {
        return repository.findAllWithPublishedDishes().stream()
            .map(c -> new DishCategorySummary(c.getName(), c.getSlug()))
            .toList();
    }

    public String findNameBySlug(String slug) {
        return repository.findBySlug(slug)
            .map(DishCategoryEntity::getName)
            .orElse(null);
    }

    public void requireExisting(String name) {
        if (repository.findByName(normalize(name)).isEmpty()) {
            throw new NotFoundException("菜品分类不存在，请先在分类管理中创建");
        }
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public AdminDishCategory create(DishCategoryRequest request) {
        var name = normalize(request.name());
        var slug = slug(name);
        requireUnique(name, slug, null);
        return response(repository.save(DishCategoryEntity.create(name, slug, description(request.description()))));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public AdminDishCategory update(long id, DishCategoryRequest request) {
        var category = entity(id);
        var oldName = category.getName();
        var name = normalize(request.name());
        var slug = slug(name);
        requireUnique(name, slug, id);
        category.update(name, slug, description(request.description()));
        dishRepository.updateCategory(oldName, name);
        return response(category);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public void delete(long id) {
        var category = entity(id);
        if (dishRepository.countByCategory(category.getName()) > 0) {
            throw new DataIntegrityViolationException("分类仍被菜品使用");
        }
        repository.delete(category);
    }

    private AdminDishCategory response(DishCategoryEntity category) {
        return new AdminDishCategory(category.getId(), category.getName(), category.getSlug(), category.getDescription(),
            dishRepository.countByCategory(category.getName()),
            dishRepository.countByCategoryAndPublishedTrue(category.getName()));
    }

    private DishCategoryEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("菜品分类不存在：" + id));
    }

    private void requireUnique(String name, String slug, Long id) {
        boolean exists = id == null
            ? repository.existsByName(name) || repository.existsBySlug(slug)
            : repository.existsByNameAndIdNot(name, id) || repository.existsBySlugAndIdNot(slug, id);
        if (exists) throw new DataIntegrityViolationException("菜品分类已存在");
    }

    private static String normalize(String value) { return value.trim(); }
    private static String slug(String value) { return value.toLowerCase(Locale.ROOT); }
    private static String description(String value) { return value == null ? "" : value.trim(); }
}
