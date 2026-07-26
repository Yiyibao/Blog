package com.yubai.blog.kitchen;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DailyMenuItemRepository extends JpaRepository<DailyMenuItemEntity, Long> {

    List<DailyMenuItemEntity> findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(long menuId);

    Optional<DailyMenuItemEntity> findByIdAndMenuId(long id, long menuId);

    long countByMenuId(long menuId);

    @Query("SELECT COALESCE(MAX(i.sortOrder), -1) FROM DailyMenuItemEntity i WHERE i.menuId = :menuId")
    int maxSortOrder(@Param("menuId") long menuId);

    /** 深链需要 slug；只取标量列，绝不 SELECT DishEntity（其 @ElementCollection 是 EAGER 批量抓取）。 */
    interface DishSlugRow {
        Long getId();
        String getSlug();
    }

    @Query("SELECT d.id as id, d.slug as slug FROM DishEntity d WHERE d.id IN :ids")
    List<DishSlugRow> findDishSlugs(@Param("ids") Collection<Long> ids);
}
