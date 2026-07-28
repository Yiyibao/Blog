package com.yubai.blog.dish;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.PageRequests;
import com.yubai.blog.config.CacheConfig;
import com.yubai.blog.storage.StorageService;

@Service
@Transactional(readOnly = true)
public class DishService {
    private static final Logger log = LoggerFactory.getLogger(DishService.class);
    private final DishRepository repository;
    private final DishCategoryService categoryService;
    private final DishAssetRepository dishAssetRepository;
    private final StorageService storageService;

    public DishService(DishRepository repository, DishCategoryService categoryService, DishAssetRepository dishAssetRepository, StorageService storageService) {
        this.repository = repository;
        this.categoryService = categoryService;
        this.dishAssetRepository = dishAssetRepository;
        this.storageService = storageService;
    }

    public PageResponse<DishResponse> findPublished(int page, int size) {
        return findPublished(page, size, null, null);
    }

    public PageResponse<DishResponse> findPublished(int page, int size, String categorySlug, String query) {
        var pageable = PageRequests.of(page, size);
        boolean hasQuery = query != null && !query.isBlank();
        String categoryName = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            categoryName = categoryService.findNameBySlug(categorySlug);
            if (categoryName == null) {
                return PageResponse.from(org.springframework.data.domain.Page.empty(pageable));
            }
        }

        if (hasQuery) {
            var likeQuery = "%" + query.trim().toLowerCase() + "%";
            if (categoryName != null) {
                return PageResponse.from(repository.searchPublishedByCategory(categoryName, likeQuery, pageable).map(DishResponse::from));
            }
            return PageResponse.from(repository.searchPublishedEntities(likeQuery, pageable).map(DishResponse::from));
        }

        if (categoryName != null) {
            return PageResponse.from(repository.findByCategoryAndPublishedTrueOrderByFeaturedDescDisplayOrderAsc(categoryName, pageable).map(DishResponse::from));
        }

        return PageResponse.from(repository.findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(pageable).map(DishResponse::from));
    }

    public DishResponse findPublishedBySlug(String slug) {
        return repository.findBySlugAndPublishedTrue(slug)
            .map(DishResponse::from)
            .orElseThrow(() -> new NotFoundException("菜品不存在：" + slug));
    }

    /** 3C：详情读带来的真实浏览计数；未命中（不存在/未发布）静默为 0，不影响详情读取流程。 */
    @Transactional
    public int registerView(String slug) {
        return repository.incrementViewsCount(slug);
    }

    /**
     * P0-7（已批准）：语义改为纯计数 favorite——每次调用 +1，不再假装 toggle。
     * P0-4：计数走数据库端原子 UPDATE。
     */
    @Transactional
    public DishFavoriteResponse favorite(String slug) {
        if (repository.incrementFavoriteCount(slug) == 0) {
            throw new NotFoundException("菜品不存在：" + slug);
        }
        var dish = repository.findBySlugAndPublishedTrue(slug)
            .orElseThrow(() -> new NotFoundException("菜品不存在：" + slug));
        return DishFavoriteResponse.from(dish);
    }

    public PageResponse<DishFavoriteItem> findFavorites(int page, int size) {
        return PageResponse.from(
            repository.findAllByPublishedTrueOrderByFavoriteCountDesc(PageRequests.of(page, size))
                .map(DishFavoriteItem::from)
        );
    }

    public PageResponse<DishResponse> findAll(int page, int size) {
        return PageResponse.from(repository.findAllByOrderByDisplayOrderAsc(PageRequests.of(page, size)).map(DishResponse::from));
    }

    public DishResponse findOne(long id) {
        return DishResponse.from(entity(id));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public DishResponse create(DishRequest request) {
        categoryService.requireExisting(request.category());
        if (repository.existsBySlug(request.slug())) {
            throw new DataIntegrityViolationException("菜品 Slug 已存在");
        }
        return DishResponse.from(repository.save(DishEntity.create(request)));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public DishResponse update(long id, DishRequest request) {
        var dish = entity(id);
        categoryService.requireExisting(request.category());
        if (repository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new DataIntegrityViolationException("菜品 Slug 已存在");
        }
        dish.update(request);
        return DishResponse.from(dish);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public void updateImageUrl(long id, String imageUrl) {
        var dish = entity(id);
        dish.updateImageUrl(imageUrl);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public void delete(long id) {
        entity(id);
        var storageKey = dishAssetRepository.findByDishId(id)
            .map(asset -> {
                var key = asset.getStorageKey();
                dishAssetRepository.delete(asset);
                return key;
            })
            .orElse(null);
        repository.deleteById(id);
        deleteStorageAfterCommit(storageKey);
    }

    private void deleteStorageAfterCommit(String storageKey) {
        if (storageKey != null && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        storageService.delete(storageKey);
                    } catch (Exception e) {
                        log.warn("Failed to delete dish asset storage {}: {}", storageKey, e.toString());
                    }
                }
            });
        } else if (storageKey != null) {
            storageService.delete(storageKey);
        }
    }

    private DishEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("菜品不存在：" + id));
    }
}
