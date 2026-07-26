package com.yubai.blog.kitchen;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageRequests;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuRequest;
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuResponse;
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuSummary;
import com.yubai.blog.kitchen.KitchenDtos.MenuItemRequest;
import com.yubai.blog.kitchen.KitchenDtos.MenuItemResponse;
import com.yubai.blog.kitchen.KitchenDtos.MenuItemUpsert;

@Service
public class DailyMenuService {

    /** 操作者身份一律来自 JWT（Controller 解 claim），DTO 不含署名分量。 */
    public record Actor(long id, String name, boolean admin) {
    }

    private final DailyMenuRepository menus;
    private final DailyMenuItemRepository items;
    private final DishRepository dishes;

    public DailyMenuService(DailyMenuRepository menus, DailyMenuItemRepository items, DishRepository dishes) {
        this.menus = menus;
        this.items = items;
        this.dishes = dishes;
    }

    /** 日期一律 String 进、Service 内 parse——绕开类型不匹配 500，错误给中文 400。 */
    public static LocalDate parseDate(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new KitchenBadRequestException("缺少 date 参数（格式 YYYY-MM-DD）");
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new KitchenBadRequestException("日期格式不对，应为 YYYY-MM-DD");
        }
    }

    @Transactional(readOnly = true)
    public DailyMenuResponse getMenu(LocalDate date) {
        return menus.findByMenuDate(date)
            .map(this::toResponse)
            .orElseGet(() -> DailyMenuResponse.empty(date));
    }

    @Transactional(readOnly = true)
    public PageResponse<DailyMenuSummary> history(int page, int size, LocalDate from, LocalDate to) {
        Page<DailyMenuEntity> result = (from != null && to != null)
            ? menus.findAllByMenuDateBetweenOrderByMenuDateDesc(from, to, PageRequests.of(page, size))
            : menus.findAllByOrderByMenuDateDesc(PageRequests.of(page, size));
        var summaries = result.getContent().stream()
            .map(menu -> new DailyMenuSummary(menu.getMenuDate(), menu.getStatus(), menu.getNote(),
                items.countByMenuId(menu.getId()), menu.getUpdatedAt()))
            .toList();
        return new PageResponse<>(summaries, result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages());
    }

    /**
     * append 加菜：可交换操作，无 expectedVersion——两人同时加菜双方都成功，
     * 这是"一起点菜"的主路径，负责消掉九成以上的 409。
     */
    @Transactional
    public DailyMenuResponse appendItem(LocalDate date, MenuItemRequest request, Actor actor) {
        var menu = findOrCreateMenu(date, actor);
        var resolved = resolveDish(request.dishSlug(), request.title());
        var sortOrder = items.maxSortOrder(menu.getId()) + 1;
        items.save(DailyMenuItemEntity.create(menu.getId(), resolved.dishId(), resolved.title(),
            request.mealSlot(), Objects.requireNonNullElse(request.note(), ""),
            sortOrder, actor.id(), actor.name()));
        // 刻意绕过 @Version 的 touch：append 不作废对方在途的全量编辑
        menus.touch(menu.getId(), actor.id(), Instant.now());
        return toResponse(menu);
    }

    /**
     * 全量 PUT：排序/定档用。expectedVersion 不符 409；
     * FORCE_INCREMENT 保证只动子表也会递增版本；按 item.id diff——
     * 既有项只更新可变字段（署名/创建时间保真），缺失的删，无 id 的插。
     */
    @Transactional
    public DailyMenuResponse putMenu(LocalDate date, DailyMenuRequest request, Actor actor) {
        var menu = menus.findWithLockByMenuDate(date)
            .orElseThrow(() -> new NotFoundException("这一天还没有菜单，请先加一道菜"));
        if (menu.getVersion() != request.expectedVersion()) {
            throw new MenuVersionConflictException();
        }
        var existing = items.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(menu.getId()).stream()
            .collect(Collectors.toMap(DailyMenuItemEntity::getId, Function.identity()));
        Map<Long, DailyMenuItemEntity> leftover = new HashMap<>(existing);
        int index = 0;
        for (MenuItemUpsert upsert : request.items()) {
            if (upsert.id() != null) {
                var item = leftover.remove(upsert.id());
                if (item == null) {
                    throw new KitchenBadRequestException("菜单里找不到 id=" + upsert.id() + " 的菜，请刷新后重试");
                }
                var resolved = resolveForUpdate(item, upsert);
                item.updateMutable(resolved.title(), upsert.mealSlot(),
                    Objects.requireNonNullElse(upsert.note(), ""), index);
                items.save(item);
            } else {
                var resolved = resolveDish(upsert.dishSlug(), upsert.title());
                items.save(DailyMenuItemEntity.create(menu.getId(), resolved.dishId(), resolved.title(),
                    upsert.mealSlot(), Objects.requireNonNullElse(upsert.note(), ""),
                    index, actor.id(), actor.name()));
            }
            index++;
        }
        items.deleteAll(leftover.values());
        menu.update(request.status(), Objects.requireNonNullElse(request.note(), ""), actor.id());
        menus.save(menu);
        return toResponse(menu);
    }

    /** 删单项：作者本人或 ADMIN；否则 403。 */
    @Transactional
    public DailyMenuResponse deleteItem(long itemId, Actor actor) {
        var item = items.findById(itemId)
            .orElseThrow(() -> new NotFoundException("这道菜已经不在菜单里了"));
        if (!actor.admin() && item.getAuthorId() != actor.id()) {
            throw new org.springframework.security.access.AccessDeniedException("只能删除自己加的菜");
        }
        var menu = menus.findById(item.getMenuId())
            .orElseThrow(() -> new NotFoundException("菜单不存在"));
        items.delete(item);
        menus.touch(menu.getId(), actor.id(), Instant.now());
        return toResponse(menu);
    }

    /** unique(menu_date) 首创竞态：ON CONFLICT DO NOTHING + 重读，无异常路径（见仓库注释）。 */
    private DailyMenuEntity findOrCreateMenu(LocalDate date, Actor actor) {
        var found = menus.findByMenuDate(date);
        if (found.isPresent()) return found.get();
        menus.insertIfAbsent(date, actor.id());
        return menus.findByMenuDate(date)
            .orElseThrow(() -> new IllegalStateException("菜单 upsert 后仍不可见"));
    }

    private record ResolvedDish(Long dishId, String title) {
    }

    private ResolvedDish resolveDish(String dishSlug, String title) {
        if (dishSlug != null && !dishSlug.isBlank()) {
            var dish = dishes.findBySlugAndPublishedTrue(dishSlug.trim())
                .orElseThrow(() -> new NotFoundException("菜谱库里没有这道菜（或未发布）"));
            return new ResolvedDish(dish.getId(), dish.getName());
        }
        if (title == null || title.isBlank()) {
            throw new KitchenBadRequestException("dishSlug 与 title 至少填一个——想吃的菜还没进菜谱库时直接写名字");
        }
        return new ResolvedDish(null, title.trim());
    }

    /** 更新既有项：换了 dishSlug 则重解析；否则保留原 dishId，title 缺省沿用原值。 */
    private ResolvedDish resolveForUpdate(DailyMenuItemEntity item, MenuItemUpsert upsert) {
        if (upsert.dishSlug() != null && !upsert.dishSlug().isBlank()) {
            return resolveDish(upsert.dishSlug(), upsert.title());
        }
        var title = (upsert.title() == null || upsert.title().isBlank()) ? item.getTitle() : upsert.title().trim();
        return new ResolvedDish(item.getDishId(), title);
    }

    private DailyMenuResponse toResponse(DailyMenuEntity menu) {
        var menuItems = items.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(menu.getId());
        var dishIds = menuItems.stream().map(DailyMenuItemEntity::getDishId).filter(Objects::nonNull).distinct().toList();
        Map<Long, String> slugs = dishIds.isEmpty() ? Map.of()
            : items.findDishSlugs(dishIds).stream()
                .collect(Collectors.toMap(DailyMenuItemRepository.DishSlugRow::getId,
                    DailyMenuItemRepository.DishSlugRow::getSlug));
        var responses = menuItems.stream()
            .map(item -> new MenuItemResponse(item.getId(), item.getDishId(),
                item.getDishId() == null ? null : slugs.get(item.getDishId()),
                item.getTitle(), item.getMealSlot(), item.getNote(), item.getSortOrder(),
                item.getAuthorId(), item.getAuthorName(), item.getCreatedAt()))
            .toList();
        return new DailyMenuResponse(true, menu.getMenuDate(), menu.getStatus(), menu.getNote(),
            menu.getVersion(), responses, menu.getUpdatedBy(), menu.getUpdatedAt());
    }
}
