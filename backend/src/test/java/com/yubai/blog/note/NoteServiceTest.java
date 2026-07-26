package com.yubai.blog.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageRequests;

@ExtendWith(MockitoExtension.class)
class NoteServiceTest {

    @Mock
    NoteRepository repository;

    @InjectMocks
    NoteService service;

    private NoteEntity mockNote(long id, String title, String status, long version) {
        var s = switch (status) {
            case "PUBLISHED" -> NoteStatus.PUBLISHED;
            case "ARCHIVED" -> NoteStatus.ARCHIVED;
            default -> NoteStatus.DRAFT;
        };
        var ns = s;
        return new NoteEntity() {
            @Override public Long getId() { return id; }
            @Override public String getTitle() { return title; }
            @Override public String getMarkdownContent() { return "# " + title; }
            @Override public String getFolder() { return "Dev"; }
            @Override public NoteStatus getStatus() { return ns; }
            @Override public List<String> getTags() { return List.of(); }
            @Override public long getVersion() { return version; }
            @Override public int getWordCount() { return title.length(); }
        };
    }

    /** L-12：列表路径 stub 轻量投影行（标签由 findTagRows 批量补取，未 stub 时为空列表即空标签）。 */
    private NoteRepository.NoteListRow mockRow(long id, String title, String status, long version) {
        var s = switch (status) {
            case "PUBLISHED" -> NoteStatus.PUBLISHED;
            case "ARCHIVED" -> NoteStatus.ARCHIVED;
            default -> NoteStatus.DRAFT;
        };
        return new NoteRepository.NoteListRow() {
            @Override public Long getId() { return id; }
            @Override public String getTitle() { return title; }
            @Override public String getFolder() { return "Dev"; }
            @Override public NoteStatus getStatus() { return s; }
            @Override public String getSourceFileName() { return null; }
            @Override public int getWordCount() { return title.length(); }
            @Override public long getVersion() { return version; }
            @Override public java.time.Instant getCreatedAt() { return java.time.Instant.EPOCH; }
            @Override public java.time.Instant getUpdatedAt() { return java.time.Instant.EPOCH; }
        };
    }

    @Test
    void findAllWithoutStatusReturnsAll() {
        var row = mockRow(1L, "Draft", "DRAFT", 0);
        when(repository.findAllByOrderByUpdatedAtDesc(any())).thenReturn(new PageImpl<>(List.of(row)));

        var result = service.findAll(null, 0, 10);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findAllWithStatusFiltersByStatus() {
        var row = mockRow(1L, "Draft", "DRAFT", 0);
        when(repository.findAllByStatusOrderByUpdatedAtDesc(NoteStatus.DRAFT, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(List.of(row)));

        var result = service.findAll(NoteStatus.DRAFT, 0, 10);
        assertThat(result.items()).hasSize(1);
    }

    // L-12：列表标签经 findTagRows 一次 IN 查询补齐
    @Test
    void findPublishedReturnsPublishedOnly() {
        var row = mockRow(2L, "Published", "PUBLISHED", 1);
        when(repository.findAllByStatusOrderByUpdatedAtDesc(NoteStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(List.of(row)));
        when(repository.findTagRows(List.of(2L))).thenReturn(List.<Object[]>of(new Object[]{2L, "vue"}));

        var result = service.findPublished(0, 10);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).tags()).containsExactly("vue");
    }

    @Test
    void findOneReturnsNote() {
        var note = mockNote(1L, "Draft", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));

        var result = service.findOne(1L);
        assertThat(result.title()).isEqualTo("Draft");
    }

    @Test
    void findOneThrowsWhenNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findOne(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findPublishedOneReturnsPublished() {
        var note = mockNote(2L, "Published", "PUBLISHED", 1);
        when(repository.findById(2L)).thenReturn(Optional.of(note));

        var result = service.findPublishedOne(2L);
        assertThat(result.status()).isEqualTo(NoteStatus.PUBLISHED);
    }

    @Test
    void findPublishedOneThrowsWhenNotPublished() {
        var note = mockNote(1L, "Draft", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.findPublishedOne(1L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createSavesAndReturnsNote() {
        when(repository.saveAndFlush(any())).thenReturn(mockNote(1L, "New", "DRAFT", 0));

        var request = new NoteRequest("New", "# New", "Dev", NoteStatus.DRAFT, List.of(), 0L);
        var result = service.create(request);
        assertThat(result).isNotNull();
    }

    @Test
    void updateSucceedsWithMatchingVersion() {
        var note = mockNote(1L, "Original", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));
        when(repository.saveAndFlush(any())).thenReturn(mockNote(1L, "Updated", "DRAFT", 1));

        var request = new NoteRequest("Updated", "# Updated", "Dev", NoteStatus.DRAFT, List.of(), 0L);
        var result = service.update(1L, request);
        assertThat(result.title()).isEqualTo("Updated");
    }

    @Test
    void updateThrowsOnVersionMismatch() {
        var note = mockNote(1L, "Original", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));

        var request = new NoteRequest("Updated", "# Updated", "Dev", NoteStatus.DRAFT, List.of(), 1L);
        assertThatThrownBy(() -> service.update(1L, request)).isInstanceOf(NoteVersionConflictException.class);
    }

    @Test
    void publishChangesStatus() {
        var note = mockNote(1L, "Draft", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));
        when(repository.saveAndFlush(any())).thenReturn(mockNote(1L, "Draft", "PUBLISHED", 1));

        var result = service.publish(1L, 0L);
        assertThat(result.status()).isEqualTo(NoteStatus.PUBLISHED);
    }

    @Test
    void unpublishChangesStatusToDraft() {
        var note = mockNote(1L, "Published", "PUBLISHED", 1);
        when(repository.findById(1L)).thenReturn(Optional.of(note));
        when(repository.saveAndFlush(any())).thenReturn(mockNote(1L, "Published", "DRAFT", 2));

        var result = service.unpublish(1L, 1L);
        assertThat(result.status()).isEqualTo(NoteStatus.DRAFT);
    }

    @Test
    void archiveChangesStatus() {
        var note = mockNote(1L, "Published", "PUBLISHED", 1);
        when(repository.findById(1L)).thenReturn(Optional.of(note));
        when(repository.saveAndFlush(any())).thenReturn(mockNote(1L, "Published", "ARCHIVED", 2));

        var result = service.archive(1L, 1L);
        assertThat(result.status()).isEqualTo(NoteStatus.ARCHIVED);
    }

    @Test
    void publishThrowsOnVersionMismatch() {
        var note = mockNote(1L, "Draft", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));

        assertThatThrownBy(() -> service.publish(1L, 99L)).isInstanceOf(NoteVersionConflictException.class);
    }

    @Test
    void deleteRemovesExistingNote() {
        var note = mockNote(1L, "Draft", "DRAFT", 0);
        when(repository.findById(1L)).thenReturn(Optional.of(note));

        service.delete(1L);
        verify(repository).delete(note);
    }
}
