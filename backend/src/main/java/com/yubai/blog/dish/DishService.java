package com.yubai.blog.dish;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.PageRequests;

@Service
@Transactional(readOnly = true)
public class DishService {
    private final DishRepository repository;

    public DishService(DishRepository repository) {
        this.repository = repository;
    }

    public PageResponse<DishResponse> findPublished(int page, int size) {
        return PageResponse.from(repository.findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(PageRequests.of(page, size))
            .map(DishResponse::from));
    }

    public DishResponse findPublishedBySlug(String slug) {
        return repository.findBySlugAndPublishedTrue(slug)
            .map(DishResponse::from)
            .orElseThrow(() -> new NotFoundException("菜品不存在：" + slug));
    }

    @Transactional
    public DishFavoriteResponse toggleFavorite(String slug) {
        var dish = repository.findBySlugAndPublishedTrue(slug)
            .orElseThrow(() -> new NotFoundException("菜品不存在：" + slug));
        dish.setFavoriteCount(dish.getFavoriteCount() + 1);
        return DishFavoriteResponse.from(dish, true);
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
    public DishResponse create(DishRequest request) {
        if (repository.existsBySlug(request.slug())) {
            throw new DataIntegrityViolationException("菜品 Slug 已存在");
        }
        return DishResponse.from(repository.save(DishEntity.create(request)));
    }

    @Transactional
    public DishResponse update(long id, DishRequest request) {
        var dish = entity(id);
        if (repository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new DataIntegrityViolationException("菜品 Slug 已存在");
        }
        dish.update(request);
        return DishResponse.from(dish);
    }

    @Transactional
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("菜品不存在：" + id);
        }
        repository.deleteById(id);
    }

    private DishEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("菜品不存在：" + id));
    }
}
