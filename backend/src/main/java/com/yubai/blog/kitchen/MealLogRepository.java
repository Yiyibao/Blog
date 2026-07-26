package com.yubai.blog.kitchen;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MealLogRepository extends JpaRepository<MealLogEntity, Long> {

    Page<MealLogEntity> findAllByOrderByLogDateDescIdDesc(Pageable pageable);

    Page<MealLogEntity> findAllByLogDateBetweenOrderByLogDateDescIdDesc(LocalDate from, LocalDate to, Pageable pageable);

    Page<MealLogEntity> findAllByDishIdOrderByLogDateDescIdDesc(long dishId, Pageable pageable);

    /** check-in 去重：同日同名同餐次只记一次（两人先后点一键打卡不重复）。 */
    boolean existsByLogDateAndTitleAndMealSlot(LocalDate logDate, String title, MealSlot mealSlot);

    /** FD-16：聚合"做过 N 次/上次时间"——纯标量投影 + join dishes 取 slug，绝不加载 DishEntity。 */
    interface DishCookRow {
        Long getDishId();
        String getSlug();
        Long getCookCount();
        LocalDate getLastCookedAt();
    }

    @Query("""
        SELECT l.dishId as dishId, d.slug as slug, COUNT(l) as cookCount, MAX(l.logDate) as lastCookedAt
        FROM MealLogEntity l, DishEntity d
        WHERE l.dishId = d.id AND l.dishId IS NOT NULL
        GROUP BY l.dishId, d.slug
        ORDER BY COUNT(l) DESC, MAX(l.logDate) DESC
        """)
    List<DishCookRow> aggregateCookStats();
}
