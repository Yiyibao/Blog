package com.yubai.blog.kitchen;

import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShoppingListRepository extends JpaRepository<ShoppingListEntity, UUID> {
    Optional<ShoppingListEntity> findByOwnerIdAndWeekStart(long ownerId, LocalDate weekStart);

    Optional<ShoppingListEntity> findByIdAndOwnerId(UUID id, long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            "select list from ShoppingListEntity list where list.id = :id and list.ownerId = :ownerId")
    Optional<ShoppingListEntity> findWithLockByIdAndOwnerId(
            @Param("id") UUID id, @Param("ownerId") long ownerId);

    @Modifying
    @Query(
            value =
                    """
        insert into shopping_lists (id, owner_id, week_start, note, version, created_at, updated_at)
        values (:id, :ownerId, :weekStart, '', 0, current_timestamp, current_timestamp)
        on conflict (owner_id, week_start) do nothing
        """,
            nativeQuery = true)
    int insertIfAbsent(
            @Param("id") UUID id,
            @Param("ownerId") long ownerId,
            @Param("weekStart") LocalDate weekStart);
}
