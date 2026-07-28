package com.yubai.blog.dish;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DishAssetRepository extends JpaRepository<DishAssetEntity, Long> {
    Optional<DishAssetEntity> findByPublicId(UUID publicId);
    Optional<DishAssetEntity> findByDishId(Long dishId);
    void deleteByDishId(Long dishId);
}
