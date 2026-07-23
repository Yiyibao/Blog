package com.yubai.blog.dish;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DishRepository extends JpaRepository<DishEntity, Long> {
    Page<DishEntity> findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(Pageable pageable);
    Page<DishEntity> findAllByOrderByDisplayOrderAsc(Pageable pageable);
    Optional<DishEntity> findBySlugAndPublishedTrue(String slug);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, long id);
}
