package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageRequests;

@ExtendWith(MockitoExtension.class)
class DishServiceTest {

    @Mock
    DishRepository repository;

    @InjectMocks
    DishService service;

    private DishEntity mockDish(long id, String slug, boolean published) {
        return new DishEntity() {
            @Override public Long getId() { return id; }
            @Override public String getSlug() { return slug; }
            @Override public String getName() { return "Test Dish"; }
            @Override public String getSummary() { return "Summary"; }
            @Override public String getCategory() { return "川菜"; }
            @Override public String getImageUrl() { return "/img.jpg"; }
            @Override public String getImageAlt() { return "alt"; }
            @Override public String getImageCredit() { return "credit"; }
            @Override public String getImageSourceUrl() { return "https://example.com"; }
            @Override public int getPrepMinutes() { return 20; }
            @Override public String getDifficulty() { return "家常"; }
            @Override public BigDecimal getRating() { return BigDecimal.valueOf(4.5); }
            @Override public boolean isFeatured() { return true; }
            @Override public boolean isPublished() { return published; }
            @Override public int getDisplayOrder() { return 1; }
            @Override public List<String> getIngredients() { return List.of("a"); }
            @Override public List<String> getSteps() { return List.of("b"); }
        };
    }

    @Test
    void findPublishedReturnsPublishedOnly() {
        var dish = mockDish(1L, "mapo-tofu", true);
        when(repository.findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(List.of(dish)));

        var result = service.findPublished(0, 10);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).name()).isEqualTo("Test Dish");
    }

    @Test
    void findPublishedBySlugReturnsDish() {
        var dish = mockDish(1L, "mapo-tofu", true);
        when(repository.findBySlugAndPublishedTrue("mapo-tofu")).thenReturn(Optional.of(dish));

        var result = service.findPublishedBySlug("mapo-tofu");
        assertThat(result.slug()).isEqualTo("mapo-tofu");
    }

    @Test
    void findPublishedBySlugThrowsWhenNotFound() {
        when(repository.findBySlugAndPublishedTrue("missing")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findPublishedBySlug("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findAllReturnsAllDishes() {
        var dish = mockDish(1L, "mapo-tofu", true);
        when(repository.findAllByOrderByDisplayOrderAsc(any())).thenReturn(new PageImpl<>(List.of(dish)));

        var result = service.findAll(0, 10);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findOneReturnsDish() {
        var dish = mockDish(1L, "mapo-tofu", true);
        when(repository.findById(1L)).thenReturn(Optional.of(dish));

        var result = service.findOne(1L);
        assertThat(result.slug()).isEqualTo("mapo-tofu");
    }

    @Test
    void findOneThrowsWhenNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findOne(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createSavesWithUniqueSlug() {
        when(repository.existsBySlug("new-dish")).thenReturn(false);
        when(repository.save(any())).thenReturn(mockDish(1L, "new-dish", true));

        var request = new DishRequest("new-dish", "新菜品", "简介", "川菜",
            "/food/new.jpg", "图片", "作者", "https://example.com",
            10, "简单", BigDecimal.valueOf(4.0), false, true, 2, List.of("原料"), List.of("步骤"));
        var result = service.create(request);
        assertThat(result).isNotNull();
    }

    @Test
    void createThrowsOnDuplicateSlug() {
        when(repository.existsBySlug("dup-dish")).thenReturn(true);

        var request = new DishRequest("dup-dish", "重复", "简介", "川菜",
            "/food/dup.jpg", "图片", "作者", "https://example.com",
            10, "简单", BigDecimal.valueOf(4.0), false, true, 2, List.of("原料"), List.of("步骤"));
        assertThatThrownBy(() -> service.create(request)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateSavesWithUniqueSlug() {
        var dish = mockDish(1L, "mapo-tofu", true);
        when(repository.findById(1L)).thenReturn(Optional.of(dish));
        when(repository.existsBySlugAndIdNot("mapo-tofu", 1L)).thenReturn(false);

        var request = new DishRequest("mapo-tofu", "更新版麻婆豆腐", "新简介", "川菜",
            "/food/mapo.jpg", "图片", "作者", "https://example.com",
            25, "家常", BigDecimal.valueOf(4.8), true, true, 1, List.of("豆腐"), List.of("炒"));
        var result = service.update(1L, request);
        assertThat(result.slug()).isEqualTo("mapo-tofu");
    }

    @Test
    void deleteRemovesExistingDish() {
        when(repository.existsById(1L)).thenReturn(true);
        service.delete(1L);
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteThrowsWhenNotFound() {
        when(repository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(NotFoundException.class);
    }
}
