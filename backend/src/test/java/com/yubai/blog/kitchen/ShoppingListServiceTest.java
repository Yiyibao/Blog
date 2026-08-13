package com.yubai.blog.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.dish.DishEntity;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.kitchen.ShoppingListDtos.UpdateRequest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ShoppingListServiceTest {
    private static final long OWNER = 7L;
    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 10);

    @Mock ShoppingListRepository lists;
    @Mock ShoppingListItemRepository items;
    @Mock DailyMenuService dailyMenus;
    @Mock DishRepository dishes;

    private ShoppingListService service;

    @BeforeEach
    void setUp() {
        service = new ShoppingListService(lists, items, dailyMenus, dishes);
    }

    @Test
    void requiresMondayWeekStart() {
        assertThat(ShoppingListService.parseWeekStart("2026-08-10")).isEqualTo(MONDAY);
        assertThatThrownBy(() -> ShoppingListService.parseWeekStart("2026-08-11"))
                .isInstanceOf(KitchenBadRequestException.class);
    }

    @Test
    void generateMergesOnlySameUnitAndRetainsRecipeSnapshot() {
        var list = list();
        when(lists.findByOwnerIdAndWeekStart(OWNER, MONDAY)).thenReturn(Optional.of(list));
        for (int offset = 0; offset < 7; offset++) {
            var menu = mock(KitchenDtos.DailyMenuResponse.class);
            when(menu.items()).thenReturn(offset < 2 ? List.of(item("rice")) : List.of());
            when(dailyMenus.getMenu(MONDAY.plusDays(offset))).thenReturn(menu);
        }
        var riceDish = dish("米饭", List.of("米 200 克", "油 1 勺"));
        when(dishes.findBySlugAndPublishedTrue("rice")).thenReturn(Optional.of(riceDish));
        when(items.findAllByListIdOrderBySortOrderAscIdAsc(list.getId())).thenReturn(List.of());
        when(items.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(lists.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(items.findAllByListIdOrderBySortOrderAscIdAsc(list.getId()))
                .thenReturn(List.of(), List.of());

        service.generate(OWNER, MONDAY, "mutation-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ShoppingListItemEntity>> captured = ArgumentCaptor.forClass(List.class);
        verify(items).saveAll(captured.capture());
        assertThat(captured.getValue()).hasSize(2);
        var rice =
                captured.getValue().stream()
                        .filter(item -> item.getNormalizedName().contains("米"))
                        .findFirst()
                        .orElseThrow();
        assertThat(rice.getQuantity()).hasToString("400");
        assertThat(rice.getUnit()).isEqualTo("克");
        assertThat(rice.getOriginalQuantity()).contains("200 克");
        assertThat(rice.getSourceRecipe()).isEqualTo("米饭");
    }

    @Test
    void updateRejectsStaleVersionAndIdempotentRetryDoesNotWriteTwice() {
        var list = list();
        when(lists.findWithLockByIdAndOwnerId(list.getId(), OWNER)).thenReturn(Optional.of(list));
        assertThatThrownBy(
                        () ->
                                service.update(
                                        OWNER,
                                        list.getId(),
                                        new UpdateRequest(4L, "", List.of()),
                                        "key-1"))
                .isInstanceOf(ShoppingListVersionConflictException.class);

        list.markMutation("key-2");
        var response =
                service.update(OWNER, list.getId(), new UpdateRequest(0L, "", List.of()), "key-2");
        assertThat(response.id()).isEqualTo(list.getId());
    }

    private ShoppingListEntity list() {
        var list = ShoppingListEntity.create(OWNER, MONDAY);
        setId(list, UUID.randomUUID());
        return list;
    }

    private KitchenDtos.MenuItemResponse item(String slug) {
        return new KitchenDtos.MenuItemResponse(
                1L, 1L, slug, slug, MealSlot.DINNER, "", 0, OWNER, "owner", Instant.now());
    }

    private DishEntity dish(String name, List<String> ingredients) {
        var dish = mock(DishEntity.class);
        when(dish.getName()).thenReturn(name);
        when(dish.getIngredients()).thenReturn(ingredients);
        return dish;
    }

    private static void setId(ShoppingListEntity entity, UUID id) {
        try {
            var field = ShoppingListEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
