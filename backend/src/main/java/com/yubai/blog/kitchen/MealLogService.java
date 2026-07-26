package com.yubai.blog.kitchen;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageRequests;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.kitchen.DailyMenuService.Actor;
import com.yubai.blog.kitchen.KitchenDtos.DishCookStat;
import com.yubai.blog.kitchen.KitchenDtos.MealLogRequest;
import com.yubai.blog.kitchen.KitchenDtos.MealLogResponse;

@Service
public class MealLogService {

    private final MealLogRepository logs;
    private final DailyMenuRepository menus;
    private final DailyMenuItemRepository menuItems;
    private final DishRepository dishes;

    public MealLogService(MealLogRepository logs, DailyMenuRepository menus,
                          DailyMenuItemRepository menuItems, DishRepository dishes) {
        this.logs = logs;
        this.menus = menus;
        this.menuItems = menuItems;
        this.dishes = dishes;
    }

    @Transactional
    public MealLogResponse create(MealLogRequest request, Actor actor) {
        var logDate = DailyMenuService.parseDate(request.logDate());
        Long dishId = null;
        String title;
        if (request.dishSlug() != null && !request.dishSlug().isBlank()) {
            var dish = dishes.findBySlugAndPublishedTrue(request.dishSlug().trim())
                .orElseThrow(() -> new NotFoundException("菜谱库里没有这道菜（或未发布）"));
            dishId = dish.getId();
            title = dish.getName();
        } else if (request.title() != null && !request.title().isBlank()) {
            title = request.title().trim();
        } else {
            throw new KitchenBadRequestException("dishSlug 与 title 至少填一个");
        }
        var saved = logs.save(MealLogEntity.create(logDate, dishId, title, request.mealSlot(),
            request.rating(), Objects.requireNonNullElse(request.note(), ""), actor.id(), actor.name()));
        return toResponse(saved);
    }

    /** FD-18 一键打卡：把某天菜单上的每道菜各记一笔；同日同名同餐次已记过的跳过（幂等）。 */
    @Transactional
    public List<MealLogResponse> checkInMenu(LocalDate date, Actor actor) {
        var menu = menus.findByMenuDate(date)
            .orElseThrow(() -> new NotFoundException("这一天还没有菜单"));
        var items = menuItems.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(menu.getId());
        if (items.isEmpty()) {
            throw new KitchenBadRequestException("菜单还是空的，先加菜再打卡");
        }
        var created = new ArrayList<MealLogResponse>();
        for (var item : items) {
            if (logs.existsByLogDateAndTitleAndMealSlot(date, item.getTitle(), item.getMealSlot())) {
                continue;
            }
            created.add(toResponse(logs.save(MealLogEntity.create(date, item.getDishId(), item.getTitle(),
                item.getMealSlot(), null, "", actor.id(), actor.name()))));
        }
        return created;
    }

    @Transactional(readOnly = true)
    public PageResponse<MealLogResponse> timeline(int page, int size, LocalDate from, LocalDate to, String dishSlug) {
        Page<MealLogEntity> result;
        if (dishSlug != null && !dishSlug.isBlank()) {
            var dish = dishes.findBySlug(dishSlug.trim())
                .orElseThrow(() -> new NotFoundException("菜谱不存在"));
            result = logs.findAllByDishIdOrderByLogDateDescIdDesc(dish.getId(), PageRequests.of(page, size));
        } else if (from != null && to != null) {
            result = logs.findAllByLogDateBetweenOrderByLogDateDescIdDesc(from, to, PageRequests.of(page, size));
        } else {
            result = logs.findAllByOrderByLogDateDescIdDesc(PageRequests.of(page, size));
        }
        var slugs = resolveSlugs(result.getContent());
        var items = result.getContent().stream().map(log -> toResponse(log, slugs)).toList();
        return new PageResponse<>(items, result.getNumber(), result.getSize(),
            result.getTotalElements(), result.getTotalPages());
    }

    @Transactional
    public void delete(long id, Actor actor) {
        var log = logs.findById(id).orElseThrow(() -> new NotFoundException("这条记录已经不在了"));
        if (!actor.admin() && log.getAuthorId() != actor.id()) {
            throw new org.springframework.security.access.AccessDeniedException("只能删除自己的打卡");
        }
        logs.delete(log);
    }

    @Transactional(readOnly = true)
    public List<DishCookStat> cookStats() {
        return logs.aggregateCookStats().stream()
            .map(row -> new DishCookStat(row.getDishId(), row.getSlug(),
                row.getCookCount(), row.getLastCookedAt()))
            .toList();
    }

    private java.util.Map<Long, String> resolveSlugs(List<MealLogEntity> content) {
        var ids = content.stream().map(MealLogEntity::getDishId).filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return java.util.Map.of();
        return menuItems.findDishSlugs(ids).stream()
            .collect(java.util.stream.Collectors.toMap(
                DailyMenuItemRepository.DishSlugRow::getId, DailyMenuItemRepository.DishSlugRow::getSlug));
    }

    private MealLogResponse toResponse(MealLogEntity log) {
        return toResponse(log, resolveSlugs(List.of(log)));
    }

    private MealLogResponse toResponse(MealLogEntity log, java.util.Map<Long, String> slugs) {
        return new MealLogResponse(log.getId(), log.getLogDate(), log.getDishId(),
            log.getDishId() == null ? null : slugs.get(log.getDishId()),
            log.getTitle(), log.getMealSlot(), log.getRating(), log.getNote(),
            log.getAuthorId(), log.getAuthorName(), log.getCreatedAt());
    }
}
