package com.yubai.blog.series;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import com.yubai.blog.post.PostRepository;
import com.yubai.blog.post.PostStatus;
import com.yubai.blog.series.SeriesDtos.SeriesEntriesRequest;
import com.yubai.blog.series.SeriesDtos.SeriesRequest;

@ExtendWith(MockitoExtension.class)
class SeriesServiceTest {

    @Mock
    SeriesRepository seriesRepository;

    @Mock
    SeriesEntryRepository entryRepository;

    @Mock
    PostRepository postRepository;

    SeriesService service() {
        return new SeriesService(seriesRepository, entryRepository, postRepository);
    }

    private static SeriesEntity series(long id, SeriesStatus status) {
        var entity = SeriesEntity.create(new SeriesRequest("测试合集", "test-series", "描述", null, status));
        setField(entity, "id", id);
        return entity;
    }

    private static SeriesEntryEntity entry(long seriesId, long postId, int order, String chapter) {
        return SeriesEntryEntity.post(seriesId, postId, order, chapter);
    }

    private static PostRepository.PostRefRow ref(long id, String slug, String title, PostStatus status) {
        return new PostRepository.PostRefRow() {
            public Long getId() { return id; }
            public String getSlug() { return slug; }
            public String getTitle() { return title; }
            public LocalDate getDate() { return LocalDate.of(2026, 7, 1); }
            public PostStatus getStatus() { return status; }
        };
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
    void updateWithStaleVersionThrowsConflict() {
        var entity = series(1L, SeriesStatus.DRAFT);
        setField(entity, "version", 3L);
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service().update(1L, 2L,
            new SeriesRequest("n", "n-slug", null, null, SeriesStatus.DRAFT)))
            .isInstanceOf(SeriesVersionConflictException.class);
    }

    @Test
    void updateFlushesBeforeMappingSoVersionMatchesDb() {
        var entity = series(1L, SeriesStatus.DRAFT);
        setField(entity, "version", 2L);
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(seriesRepository.existsBySlugAndIdNot("new-slug", 1L)).thenReturn(false);
        when(entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());

        var response = service().update(1L, 2L,
            new SeriesRequest("新的合集名", "new-slug", "新描述", null, SeriesStatus.PUBLISHED));

        verify(seriesRepository).flush();
    }

    @Test
    void setEntriesRejectsDuplicatePost() {
        var entity = series(1L, SeriesStatus.DRAFT);
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));

        var request = new SeriesEntriesRequest(List.of(
            new SeriesEntriesRequest.EntryInput(7L, null),
            new SeriesEntriesRequest.EntryInput(7L, null)), 0L);

        assertThatThrownBy(() -> service().setEntries(1L, request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("重复");
    }

    @Test
    void setEntriesRejectsUnknownPost() {
        var entity = series(1L, SeriesStatus.DRAFT);
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(postRepository.findRefRows(anyCollection())).thenReturn(List.of());

        var request = new SeriesEntriesRequest(List.of(new SeriesEntriesRequest.EntryInput(99L, null)), 0L);

        assertThatThrownBy(() -> service().setEntries(1L, request))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("99");
    }

    @Test
    void setEntriesReplacesWholeTableInSubmittedOrder() {
        var entity = series(1L, SeriesStatus.DRAFT);
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(postRepository.findRefRows(anyCollection())).thenReturn(List.of(
            ref(7L, "a", "文章A", PostStatus.PUBLISHED),
            ref(8L, "b", "文章B", PostStatus.PUBLISHED)));
        when(entryRepository.saveAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var response = service().setEntries(1L, new SeriesEntriesRequest(List.of(
            new SeriesEntriesRequest.EntryInput(8L, "上篇"),
            new SeriesEntriesRequest.EntryInput(7L, null)), 0L));

        verify(entryRepository).deleteAllBySeriesId(1L);
        verify(seriesRepository).flush();
        assertThat(response.entries()).hasSize(2);
        assertThat(response.entries().get(0).postId()).isEqualTo(8L);
        assertThat(response.entries().get(0).chapterTitle()).isEqualTo("上篇");
        assertThat(response.entries().get(0).position()).isEqualTo(1);
        assertThat(response.entries().get(1).postId()).isEqualTo(7L);
        assertThat(response.entries().get(1).position()).isEqualTo(2);
    }

    @Test
    void adminViewMarksDeletedPosts() {
        var entity = series(1L, SeriesStatus.DRAFT);
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(1L))
            .thenReturn(List.of(entry(1L, 7L, 0, null)));
        when(postRepository.findRefRows(anyCollection())).thenReturn(List.of());

        var response = service().findAdminOne(1L);

        assertThat(response.entries().get(0).title()).isEqualTo("（文章已删除）");
    }

    @Test
    void publicDetailFiltersUnpublishedMembersAndRenumbers() {
        var entity = series(1L, SeriesStatus.PUBLISHED);
        when(seriesRepository.findBySlugAndStatus("test-series", SeriesStatus.PUBLISHED))
            .thenReturn(Optional.of(entity));
        when(entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
            entry(1L, 7L, 0, null), entry(1L, 8L, 1, null), entry(1L, 9L, 2, null)));
        when(postRepository.findRefRows(anyCollection())).thenReturn(List.of(
            ref(7L, "a", "文章A", PostStatus.PUBLISHED),
            ref(8L, "b", "草稿B", PostStatus.DRAFT),
            ref(9L, "c", "文章C", PostStatus.PUBLISHED)));

        var detail = service().findPublishedBySlug("test-series");

        assertThat(detail.entries()).hasSize(2);
        assertThat(detail.entries().get(0).slug()).isEqualTo("a");
        assertThat(detail.entries().get(0).position()).isEqualTo(1);
        assertThat(detail.entries().get(1).slug()).isEqualTo("c");
        assertThat(detail.entries().get(1).position()).isEqualTo(2);
    }

    @Test
    void seriesRefUsesRenumberedPositionAndTotal() {
        var entity = series(1L, SeriesStatus.PUBLISHED);
        when(entryRepository.findAllByContentTypeAndContentId(SeriesEntryEntity.TYPE_POST, 9L))
            .thenReturn(List.of(entry(1L, 9L, 2, null)));
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));
        when(seriesRepository.findBySlugAndStatus("test-series", SeriesStatus.PUBLISHED))
            .thenReturn(Optional.of(entity));
        when(entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of(
            entry(1L, 7L, 0, null), entry(1L, 8L, 1, null), entry(1L, 9L, 2, null)));
        when(postRepository.findRefRows(anyCollection())).thenReturn(List.of(
            ref(7L, "a", "文章A", PostStatus.PUBLISHED),
            ref(8L, "b", "草稿B", PostStatus.DRAFT),
            ref(9L, "c", "文章C", PostStatus.PUBLISHED)));

        var ref = service().seriesRefForPost(9L);

        assertThat(ref).isNotNull();
        assertThat(ref.slug()).isEqualTo("test-series");
        assertThat(ref.position()).isEqualTo(2);
        assertThat(ref.total()).isEqualTo(2);
    }

    @Test
    void seriesRefIsNullWhenOnlyDraftSeriesContainsPost() {
        var entity = series(1L, SeriesStatus.DRAFT);
        when(entryRepository.findAllByContentTypeAndContentId(SeriesEntryEntity.TYPE_POST, 9L))
            .thenReturn(List.of(entry(1L, 9L, 0, null)));
        when(seriesRepository.findById(1L)).thenReturn(Optional.of(entity));

        assertThat(service().seriesRefForPost(9L)).isNull();
    }

    @Test
    void removeEntriesForPostDelegatesToRepository() {
        service().removeEntriesForPost(42L);

        verify(entryRepository).deleteAllByContent(SeriesEntryEntity.TYPE_POST, 42L);
    }
}
