package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.yubai.blog.common.NotFoundException;

@ExtendWith(MockitoExtension.class)
class DishCategoryServiceTest {
    @Mock DishCategoryRepository repository;
    @Mock DishRepository dishRepository;
    @InjectMocks DishCategoryService service;

    @Test
    void createsTrimmedCategory() {
        when(repository.save(any())).thenAnswer(invocation -> {
            var category = invocation.<DishCategoryEntity>getArgument(0);
            setId(category, 1L);
            return category;
        });
        var result = service.create(new DishCategoryRequest(" 十分钟菜 ", " 快手家常菜 "));
        assertThat(result.name()).isEqualTo("十分钟菜");
        assertThat(result.description()).isEqualTo("快手家常菜");
    }

    @Test
    void rejectsUnknownCategoryForDishes() {
        when(repository.findByName("不存在")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireExisting("不存在")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void renamesCategoryAndUpdatesDishes() {
        var category = category(2L, "旧分类");
        when(repository.findById(2L)).thenReturn(Optional.of(category));
        assertThat(service.update(2L, new DishCategoryRequest("新分类", "")).name()).isEqualTo("新分类");
        verify(dishRepository).updateCategory("旧分类", "新分类");
    }

    @Test
    void findAllPublicReturnsOnlyCategoriesWithPublishedDishes() {
        var catWithPublished = category(10L, "有发布菜");
        var catWithoutDishes = category(11L, "空分类");
        var catWithUnpublished = category(12L, "仅草稿");
        when(repository.findAllWithPublishedDishes()).thenReturn(List.of(catWithPublished));
        var result = service.findAllPublic();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).name()).isEqualTo("有发布菜");
    }

    @Test
    void refusesToDeleteUsedCategory() {
        var category = category(3L, "使用中");
        when(repository.findById(3L)).thenReturn(Optional.of(category));
        when(dishRepository.countByCategory("使用中")).thenReturn(1L);
        assertThatThrownBy(() -> service.delete(3L)).isInstanceOf(DataIntegrityViolationException.class);
        verify(repository, never()).delete(any());
    }

    private static DishCategoryEntity category(long id, String name) {
        var category = DishCategoryEntity.create(name, name, "");
        setId(category, id);
        return category;
    }

    private static void setId(DishCategoryEntity category, long id) {
        try {
            Field field = DishCategoryEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(category, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
