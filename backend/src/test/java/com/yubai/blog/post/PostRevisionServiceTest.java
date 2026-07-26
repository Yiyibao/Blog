package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yubai.blog.common.NotFoundException;

@ExtendWith(MockitoExtension.class)
class PostRevisionServiceTest {

    @Mock
    PostRevisionRepository revisionRepository;

    @Mock
    PostRepository postRepository;

    PostRevisionService service() {
        return new PostRevisionService(revisionRepository, postRepository);
    }

    private static PostEntity post(long id, String title, String markdown) {
        var request = new PostRequest("rev-post", title, "摘要", LocalDate.of(2026, 7, 27), 3,
            "工程实践", List.of("rev"), "#112233", "R1", false, PostStatus.DRAFT,
            null, markdown, ContentFormat.MARKDOWN);
        var entity = PostEntity.create(request, new PostContentSanitizer());
        setField(entity, "id", id);
        return entity;
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void recordSnapshotsAndTruncatesToKeepLimit() {
        var entity = post(7L, "标题", "# v1");
        when(postRepository.findById(7L)).thenReturn(Optional.of(entity));
        var stored = new ArrayList<PostRevisionEntity>();
        for (int i = 0; i < PostRevisionService.KEEP + 2; i++) {
            stored.add(PostRevisionEntity.snapshot(entity));
        }
        when(revisionRepository.findAllByPostIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(stored);

        service().record(7L);

        verify(revisionRepository).save(any(PostRevisionEntity.class));
        ArgumentCaptor<List<PostRevisionEntity>> captor = ArgumentCaptor.captor();
        verify(revisionRepository).deleteAll(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    void recordForMissingPostThrowsNotFound() {
        when(postRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().record(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findOneScopesRevisionToPost() {
        when(postRepository.existsById(7L)).thenReturn(true);
        when(revisionRepository.findByIdAndPostId(5L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findOne(7L, 5L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restoreAppliesSnapshotFieldsAndRecordsNewRevision() {
        var entity = post(7L, "改后的标题", "# v2");
        when(postRepository.findById(7L)).thenReturn(Optional.of(entity));

        var old = post(7L, "原标题", "# v1");
        var revision = PostRevisionEntity.snapshot(old);
        setField(revision, "id", 41L);
        when(revisionRepository.findByIdAndPostId(41L, 7L)).thenReturn(Optional.of(revision));
        when(revisionRepository.findAllByPostIdOrderByCreatedAtDescIdDesc(7L)).thenReturn(List.of());

        var response = service().restore(7L, 41L);

        assertThat(response.title()).isEqualTo("原标题");
        assertThat(response.markdownContent()).isEqualTo("# v1");
        assertThat(entity.getTitle()).isEqualTo("原标题");
        // 恢复本身也是一次保存：产生新快照
        verify(revisionRepository).save(any(PostRevisionEntity.class));
    }
}
