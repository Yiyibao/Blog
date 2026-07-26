package com.yubai.blog.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
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
import com.yubai.blog.kitchen.KitchenDtos.DailyMenuRequest;
import com.yubai.blog.kitchen.KitchenDtos.MenuItemRequest;
import com.yubai.blog.kitchen.KitchenDtos.MenuItemUpsert;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // 共享构造 helper 会带少量未消费桩，宽松模式免误报
class DailyMenuServiceTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 28);
    private static final DailyMenuService.Actor OWNER = new DailyMenuService.Actor(1L, "站长", true);
    private static final DailyMenuService.Actor PARTNER = new DailyMenuService.Actor(2L, "小伙伴", false);

    @Mock
    private DailyMenuRepository menus;
    @Mock
    private DailyMenuItemRepository items;
    @Mock
    private DishRepository dishes;

    private DailyMenuService service;

    @BeforeEach
    void setUp() {
        service = new DailyMenuService(menus, items, dishes);
    }

    private DailyMenuEntity menu() {
        var menu = mock(DailyMenuEntity.class);
        when(menu.getId()).thenReturn(10L);
        return menu;
    }

    private DishEntity dish(long id, String name) {
        var dish = mock(DishEntity.class);
        when(dish.getId()).thenReturn(id);
        when(dish.getName()).thenReturn(name);
        return dish;
    }

    @Test
    void parseDateRejectsGarbageWithChineseMessage() {
        assertThatThrownBy(() -> DailyMenuService.parseDate("not-a-date"))
            .isInstanceOf(KitchenBadRequestException.class)
            .hasMessageContaining("YYYY-MM-DD");
        assertThatThrownBy(() -> DailyMenuService.parseDate(" "))
            .isInstanceOf(KitchenBadRequestException.class);
        assertThat(DailyMenuService.parseDate(" 2026-07-28 ")).isEqualTo(DATE);
    }

    @Test
    void getMenuReturnsEmptyShellInsteadOf404() {
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.empty());
        var response = service.getMenu(DATE);
        assertThat(response.exists()).isFalse();
        assertThat(response.date()).isEqualTo(DATE);
        assertThat(response.items()).isEmpty();
    }

    @Test
    void appendResolvesDishSlugToSnapshotTitle() {
        var menu = menu();
        var mapo = dish(5L, "麻婆豆腐");
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.of(menu));
        when(dishes.findBySlugAndPublishedTrue("mapo-tofu")).thenReturn(Optional.of(mapo));
        when(items.maxSortOrder(10L)).thenReturn(2);
        when(items.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(10L)).thenReturn(List.of());

        service.appendItem(DATE, new MenuItemRequest("mapo-tofu", null, MealSlot.DINNER, null), PARTNER);

        var captor = ArgumentCaptor.forClass(DailyMenuItemEntity.class);
        verify(items).save(captor.capture());
        assertThat(captor.getValue().getTitle()).as("快照菜谱名").isEqualTo("麻婆豆腐");
        assertThat(captor.getValue().getDishId()).isEqualTo(5L);
        assertThat(captor.getValue().getSortOrder()).isEqualTo(3);
        assertThat(captor.getValue().getAuthorId()).isEqualTo(2L);
        assertThat(captor.getValue().getAuthorName()).isEqualTo("小伙伴");
        // append 走绕过 @Version 的 touch，而非 save 父实体
        verify(menus).touch(anyLong(), anyLong(), any());
        verify(menus, never()).save(any());
    }

    @Test
    void appendAcceptsFreeTextWhenDishNotInLibrary() {
        var menu = menu();
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.of(menu));
        when(items.maxSortOrder(10L)).thenReturn(-1);
        when(items.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(10L)).thenReturn(List.of());

        service.appendItem(DATE, new MenuItemRequest(null, " 楼下的烤冷面 ", MealSlot.SNACK, "加蛋"), PARTNER);

        var captor = ArgumentCaptor.forClass(DailyMenuItemEntity.class);
        verify(items).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("楼下的烤冷面");
        assertThat(captor.getValue().getDishId()).isNull();
    }

    @Test
    void appendRejectsWhenNeitherSlugNorTitle() {
        var menu = menu();
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.appendItem(DATE,
            new MenuItemRequest(null, "  ", MealSlot.DINNER, null), OWNER))
            .isInstanceOf(KitchenBadRequestException.class);
    }

    @Test
    void appendUnknownDishSlugIs404() {
        var menu = menu();
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.of(menu));
        when(dishes.findBySlugAndPublishedTrue("ghost")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.appendItem(DATE,
            new MenuItemRequest("ghost", null, MealSlot.DINNER, null), OWNER))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void appendCreatesMenuViaConflictFreeUpsertWhenAbsent() {
        var menu = menu();
        when(menus.findByMenuDate(DATE)).thenReturn(Optional.empty(), Optional.of(menu));
        when(items.maxSortOrder(10L)).thenReturn(-1);
        when(items.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(10L)).thenReturn(List.of());

        service.appendItem(DATE, new MenuItemRequest(null, "面条", MealSlot.LUNCH, null), OWNER);

        verify(menus).insertIfAbsent(DATE, 1L);
    }

    @Test
    void putMenuWithStaleVersionConflicts() {
        var menu = menu();
        when(menu.getVersion()).thenReturn(4L);
        when(menus.findWithLockByMenuDate(DATE)).thenReturn(Optional.of(menu));
        assertThatThrownBy(() -> service.putMenu(DATE,
            new DailyMenuRequest(MenuStatus.CONFIRMED, "", 3L, List.of()), OWNER))
            .isInstanceOf(MenuVersionConflictException.class);
    }

    @Test
    void putMenuDiffKeepsAuthorOfExistingItemsAndDeletesMissing() {
        var menu = menu();
        when(menu.getVersion()).thenReturn(0L);
        when(menus.findWithLockByMenuDate(DATE)).thenReturn(Optional.of(menu));
        var kept = DailyMenuItemEntity.create(10L, null, "白粥", MealSlot.BREAKFAST, "", 0, 2L, "小伙伴");
        setId(kept, 100L);
        var gone = DailyMenuItemEntity.create(10L, null, "馒头", MealSlot.BREAKFAST, "", 1, 2L, "小伙伴");
        setId(gone, 101L);
        when(items.findAllByMenuIdOrderByMealSlotAscSortOrderAscIdAsc(10L))
            .thenReturn(List.of(kept, gone), List.of(kept));
        when(items.findDishSlugs(anyCollection())).thenReturn(List.of());

        service.putMenu(DATE, new DailyMenuRequest(MenuStatus.CONFIRMED, "今晚清淡", 0L,
            List.of(new MenuItemUpsert(100L, null, "白粥（改良版）", MealSlot.DINNER, null))), OWNER);

        // 既有项被更新但署名保真（仍是 partner 的），缺失项被删
        assertThat(kept.getTitle()).isEqualTo("白粥（改良版）");
        assertThat(kept.getMealSlot()).isEqualTo(MealSlot.DINNER);
        assertThat(kept.getAuthorId()).as("PUT 不得改写署名").isEqualTo(2L);
        assertThat(kept.getAuthorName()).isEqualTo("小伙伴");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<DailyMenuItemEntity>> deleted = ArgumentCaptor.forClass(Iterable.class);
        verify(items).deleteAll(deleted.capture());
        assertThat(deleted.getValue()).containsExactly(gone);
        verify(menu).update(MenuStatus.CONFIRMED, "今晚清淡", 1L);
    }

    @Test
    void deleteItemRequiresAuthorOrAdmin() {
        var item = DailyMenuItemEntity.create(10L, null, "白粥", MealSlot.BREAKFAST, "", 0, 1L, "站长");
        setId(item, 200L);
        when(items.findById(200L)).thenReturn(Optional.of(item));
        assertThatThrownBy(() -> service.deleteItem(200L, PARTNER))
            .isInstanceOf(AccessDeniedException.class);
        verify(items, never()).delete(any(DailyMenuItemEntity.class));
    }

    /** 实体无公开 id setter（DB 生成），测试经反射注入。 */
    private static void setId(DailyMenuItemEntity entity, long id) {
        try {
            var field = DailyMenuItemEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
