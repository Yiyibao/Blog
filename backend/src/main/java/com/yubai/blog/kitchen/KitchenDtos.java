package com.yubai.blog.kitchen;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * FD-10：kitchen 菜单契约。DTO 一律 record；
 * 刻意不含 author 与 createdBy 分量——署名只信服务端从 JWT 取的身份（security S11）。
 */
public final class KitchenDtos {
    private KitchenDtos() {
    }

    /** append 加菜：dishSlug 与 title 至少给一个（有 slug 则 title 取菜谱名快照）。 */
    public record MenuItemRequest(
        @Size(max = 120) String dishSlug,
        @Size(max = 120) String title,
        @NotNull MealSlot mealSlot,
        @Size(max = 200) String note
    ) {
    }

    /** 全量 PUT 的子项：id 为空表示新增；已存在的项只更新可变字段（署名保真）。 */
    public record MenuItemUpsert(
        Long id,
        @Size(max = 120) String dishSlug,
        @Size(max = 120) String title,
        @NotNull MealSlot mealSlot,
        @Size(max = 200) String note
    ) {
    }

    /** 全量 PUT：排序/定档/批量编辑用；expectedVersion 不符即 409。元素级联校验必须显式 @Valid。 */
    public record DailyMenuRequest(
        @NotNull MenuStatus status,
        @Size(max = 500) String note,
        @NotNull Long expectedVersion,
        @NotNull @Size(max = 30) List<@Valid MenuItemUpsert> items
    ) {
    }

    public record MenuItemResponse(
        long id,
        Long dishId,
        String dishSlug,
        String title,
        MealSlot mealSlot,
        String note,
        int sortOrder,
        long authorId,
        String authorName,
        Instant createdAt
    ) {
    }

    /** 菜单不存在时返回 200 + exists:false 空壳（前端空态渲染，不把"今天还没定"当错误）。 */
    public record DailyMenuResponse(
        boolean exists,
        LocalDate date,
        MenuStatus status,
        String note,
        Long version,
        List<MenuItemResponse> items,
        Long updatedBy,
        Instant updatedAt
    ) {
        public static DailyMenuResponse empty(LocalDate date) {
            return new DailyMenuResponse(false, date, MenuStatus.DRAFT, "", null, List.of(), null, null);
        }
    }

    public record DailyMenuSummary(
        LocalDate date,
        MenuStatus status,
        String note,
        long itemCount,
        Instant updatedAt
    ) {
    }
}
