package com.yubai.blog.dish;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DishCategoryRepository extends JpaRepository<DishCategoryEntity, Long> {
    List<DishCategoryEntity> findAllByOrderByNameAsc();
    Optional<DishCategoryEntity> findByName(String name);
    boolean existsByName(String name);
    boolean existsByNameAndIdNot(String name, long id);
    boolean existsBySlug(String slug);
    boolean existsBySlugAndIdNot(String slug, long id);
    Optional<DishCategoryEntity> findBySlug(String slug);

    @Query("SELECT c FROM DishCategoryEntity c WHERE c.name IN (SELECT DISTINCT d.category FROM DishEntity d WHERE d.published = true) ORDER BY c.name ASC")
    List<DishCategoryEntity> findAllWithPublishedDishes();
}
