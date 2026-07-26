package com.yubai.blog.kitchen;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface DailyMenuRepository extends JpaRepository<DailyMenuEntity, Long> {

    Optional<DailyMenuEntity> findByMenuDate(LocalDate menuDate);

    /**
     * FD-10：全量 PUT 必须走这条加锁读取——OPTIMISTIC_FORCE_INCREMENT 保证
     * 即使本次只动了子表（父实体无脏字段），提交时也强制递增 version，
     * 让并发的另一次 PUT 如实拿到 409。
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT m FROM DailyMenuEntity m WHERE m.menuDate = :menuDate")
    Optional<DailyMenuEntity> findWithLockByMenuDate(@Param("menuDate") LocalDate menuDate);

    Page<DailyMenuEntity> findAllByMenuDateBetweenOrderByMenuDateDesc(LocalDate from, LocalDate to, Pageable pageable);

    Page<DailyMenuEntity> findAllByOrderByMenuDateDesc(Pageable pageable);

    /** append 是可交换操作，刻意绕过 @Version（不作废并行编辑），只原子刷新页脚元信息。 */
    @Modifying
    @Query("UPDATE DailyMenuEntity m SET m.updatedBy = :editorId, m.updatedAt = :now WHERE m.id = :menuId")
    int touch(@Param("menuId") long menuId, @Param("editorId") long editorId, @Param("now") Instant now);

    /**
     * FD-10：首创竞态的无异常解法——unique(menu_date) 撞车时 DO NOTHING，随后重读必命中。
     * 刻意不用"catch DataIntegrityViolation 再重读"：约束冲突会把当前 PG 事务标记为 aborted，
     * 同事务里的后续查询直接失败；REQUIRES_NEW + this 自调用又会被代理绕过，都是坑。
     */
    @Modifying
    @Query(value = """
        INSERT INTO daily_menus (menu_date, status, note, created_by, updated_by, version, created_at, updated_at)
        VALUES (:menuDate, 'DRAFT', '', :creatorId, :creatorId, 0, now(), now())
        ON CONFLICT (menu_date) DO NOTHING
        """, nativeQuery = true)
    int insertIfAbsent(@Param("menuDate") LocalDate menuDate, @Param("creatorId") long creatorId);
}
