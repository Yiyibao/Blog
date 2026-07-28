package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import com.yubai.blog.common.NotFoundException;

@ExtendWith(MockitoExtension.class)
class PostCategoryServiceTest {
    @Mock PostCategoryRepository repository;
    @Mock PostRepository postRepository;
    @InjectMocks PostCategoryService service;

    @Test
    void createsTrimmedCategory() {
        when(repository.save(any())).thenAnswer(invocation -> {
            var category = invocation.<PostCategoryEntity>getArgument(0);
            setId(category, 1L);
            return category;
        });

        var result = service.create(new PostCategoryRequest(" 工程实践 ", " 服务端与架构 "));

        assertThat(result.name()).isEqualTo("工程实践");
        assertThat(result.slug()).isEqualTo("工程实践");
        assertThat(result.description()).isEqualTo("服务端与架构");
    }

    @Test
    void rejectsUnknownCategoryForPosts() {
        when(repository.findByName("不存在")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requireExisting("不存在"))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void renamesCategoryAndUpdatesPostSnapshots() {
        var category = category(2L, "旧类别");
        when(repository.findById(2L)).thenReturn(Optional.of(category));

        var result = service.update(2L, new PostCategoryRequest("新类别", "说明"));

        assertThat(result.name()).isEqualTo("新类别");
        verify(postRepository).updateCategory("旧类别", "新类别", "新类别");
    }

    @Test
    void refusesToDeleteUsedCategory() {
        var category = category(3L, "使用中");
        when(repository.findById(3L)).thenReturn(Optional.of(category));
        when(postRepository.countByCategory("使用中")).thenReturn(1L);

        assertThatThrownBy(() -> service.delete(3L)).isInstanceOf(DataIntegrityViolationException.class);
        verify(repository, never()).delete(any());
    }

    private static PostCategoryEntity category(long id, String name) {
        var category = PostCategoryEntity.create(name, name, "");
        setId(category, id);
        return category;
    }

    private static void setId(PostCategoryEntity category, long id) {
        try {
            Field field = PostCategoryEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(category, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
