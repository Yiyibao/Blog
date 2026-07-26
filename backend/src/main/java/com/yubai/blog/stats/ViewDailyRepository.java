package com.yubai.blog.stats;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ViewDailyRepository extends JpaRepository<ViewDailyEntity, LocalDate> {

    /** 数据库端原子 UPSERT——并发详情读不丢计数（同 P0-4 手法）。 */
    @Modifying
    @Query(value = "INSERT INTO view_daily (day, views) VALUES (:day, 1) "
        + "ON CONFLICT (day) DO UPDATE SET views = view_daily.views + 1", nativeQuery = true)
    void upsertIncrement(@Param("day") LocalDate day);

    @Modifying
    @Query("DELETE FROM ViewDailyEntity v WHERE v.day < :cutoff")
    void deleteOlderThan(@Param("cutoff") LocalDate cutoff);

    List<ViewDailyEntity> findAllByDayGreaterThanEqualOrderByDayAsc(LocalDate since);
}
