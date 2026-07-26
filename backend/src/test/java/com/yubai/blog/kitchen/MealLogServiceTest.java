package com.yubai.blog.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.access.AccessDeniedException;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.dish.DishEntity;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.kitchen.KitchenDtos.MealLogRequest;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MealLogServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 8, 10);
    private static final DailyMenuService.Actor OWNER = new DailyMenuService.Actor(1L, "站长", true);
    private static final DailyMenuService.Actor PARTNER = new DailyMenuService.Actor(2L, "小伙伴", false);

    @Mock
    private MealLogRepository logs;
    @Mock
    private DailyMenuRepository menus;
    @Mock
    private DailyMenuItemRepository menuItems;
    @Mock
    private DishRepository dishes;

    private MealLogService service;

    @BeforeEach
    void setUp() {
        service = new MealLogService(logs, menus, menuItems, dishes);
        // 模拟 DB 生成主键：save 时经反射注入 id（toResponse 会拆箱 getId）
        when(logs.save(any())).thenAnswer(invocation -> {
            MealLogEntity entity = invocation.getArgument(0);
            var field = MealLogEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, 99L);
            return entity;
        });
        when(menuItems.findDishSlugs(any())).thenReturn(List.of());
    }

    @Test
    void createSnapshotsDishNameAndAuthor() {
        var dish = mock(DishEntity.class);
        when(dish.getId()).thenReturn(5L);
        when(dish.getName()).thenReturn("麻婆豆腐");
        when(dishes.findBySlugAndPublishedTrue("mapo")).thenReturn(Optional.of(dish));

        var response = service.create(new MealLogRequest("mapo", null, MealSlot.DINNER, "2026-08-10", 5, "很嫩"), PARTNER);

        assertThat(response.title()).isEqualTo("麻婆豆腐");
        assertThat(response.dishId()).isEqualTo(5L);
        assertThat(response.authorName()).isEqualTo("小伙伴");
        assertThat(response.rating()).isEqualTo(5);
        assertThat(response.logDate()).isEqualTo(DATE);
    }

    @Test
    void createRejectsMissingBothSlugAndTitle() {
        assertThatThrownBy(() -> service.create(new MealLogRequest(null, " ", MealSlot.DINNER, "2026-08-10", null, null), OWNER))
            .isInstanceOf(KitchenBadRequestException.class);
        verify(logs, never()).save(any());
    }

    @Test
    void checkInSkipsAlreadyLoggedItems() {
        var menu = mock(DailyMenuEntity.class);
        when(menu.getId()).thenReturn(10L);
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.of(menu));
        var logged = DailyMenuItemEntity.create(10L, null, "已记过的菜", MealSlot.DINNER, "", 0, 1L, "站长");
        var fresh = DailyMenuItemEntity.create(10L, 5L, "新菜", MealSlot.DINNER, "", 1, 2L, "小伙伴");
        when(menuItems.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(10L)).thenReturn(List.of(logged, fresh));
        when(logs.existsByLogDateAndTitleAndMealSlot(DATE, "已记过的菜", MealSlot.DINNER)).thenReturn(true);
        when(logs.existsByLogDateAndTitleAndMealSlot(DATE, "新菜", MealSlot.DINNER)).thenReturn(false);

        var created = service.checkInMenu(DATE, OWNER);

        assertThat(created).hasSize(1);
        assertThat(created.get(0).title()).isEqualTo("新菜");
        var captor = ArgumentCaptor.forClass(MealLogEntity.class);
        verify(logs).save(captor.capture());
        assertThat(captor.getValue().getAuthorId()).as("打卡署名是执行者").isEqualTo(1L);
    }

    @Test
    void checkInRejectsEmptyMenu() {
        var menu = mock(DailyMenuEntity.class);
        when(menu.getId()).thenReturn(10L);
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.of(menu));
        when(menuItems.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(10L)).thenReturn(List.of());
        assertThatThrownBy(() -> service.checkInMenu(DATE, OWNER))
            .isInstanceOf(KitchenBadRequestException.class);
    }

    @Test
    void checkInWithoutMenuIs404() {
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.checkInMenu(DATE, OWNER))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRequiresAuthorOrAdmin() {
        var log = MealLogEntity.create(DATE, null, "站长的记录", MealSlot.DINNER, null, "", 1L, "站长");
        when(logs.findById(7L)).thenReturn(Optional.of(log));
        assertThatThrownBy(() -> service.delete(7L, PARTNER))
            .isInstanceOf(AccessDeniedException.class);
        verify(logs, never()).delete(any(MealLogEntity.class));
    }

    @Test
    void cookStatsMapsAggregateRows() {
        var row = mock(MealLogRepository.DishCookRow.class);
        when(row.getDishId()).thenReturn(5L);
        when(row.getSlug()).thenReturn("mapo");
        when(row.getCookCount()).thenReturn(3L);
        when(row.getLastCookedAt()).thenReturn(DATE);
        when(logs.aggregateCookStats()).thenReturn(List.of(row));

        var stats = service.cookStats();

        assertThat(stats).hasSize(1);
        assertThat(stats.get(0).cookCount()).isEqualTo(3L);
        assertThat(stats.get(0).slug()).isEqualTo("mapo");
        assertThat(stats.get(0).lastCookedAt()).isEqualTo(DATE);
    }
}
