package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageRequests;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock
    PostRepository repository;

    @Mock
    PostContentSanitizer sanitizer;

    @InjectMocks
    PostService service;

    private PostEntity samplePost() {
        var post = new PostEntity();
        // Use reflection-free approach: PostEntity fields are private with package-level constructor
        // We rely on the repository mock returning entities created via the full constructor path
        return PostEntity.create(new PostRequest("test-slug", "Test Title", "Excerpt", LocalDate.of(2026, 1, 1),
            5, "工程实践", List.of("tag1"), "#000000", "01", false, PostStatus.PUBLISHED, "<p>content</p>"), sanitizer);
    }

    @Test
    void findPublishedDelegatesToRepository() {
        var posts = List.of(samplePost());
        when(repository.findAllByStatusOrderByDateDesc(PostStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(posts));
        when(sanitizer.sanitize(any())).thenReturn("<p>content</p>");

        var result = service.findPublished(0, 10);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).slug()).isEqualTo("test-slug");
    }

    @Test
    void findAdminWithoutStatusReturnsAll() {
        var posts = List.of(samplePost());
        when(repository.findAllByOrderByDateDesc(any(Pageable.class))).thenReturn(new PageImpl<>(posts));
        when(sanitizer.sanitize(any())).thenReturn("<p>content</p>");

        var result = service.findAdmin(null, 0, 10);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findAdminWithStatusFiltersByStatus() {
        var posts = List.of(samplePost());
        when(repository.findAllByStatusOrderByDateDesc(PostStatus.DRAFT, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(posts));
        when(sanitizer.sanitize(any())).thenReturn("<p>content</p>");

        var result = service.findAdmin(PostStatus.DRAFT, 0, 10);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findPublishedBySlugReturnsPost() {
        var post = samplePost();
        when(repository.findBySlugAndStatus("test-slug", PostStatus.PUBLISHED)).thenReturn(Optional.of(post));
        when(sanitizer.sanitize(any())).thenReturn("<p>content</p>");

        var result = service.findPublishedBySlug("test-slug");
        assertThat(result.slug()).isEqualTo("test-slug");
    }

    @Test
    void findPublishedBySlugThrowsWhenNotFound() {
        when(repository.findBySlugAndStatus("missing", PostStatus.PUBLISHED)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findPublishedBySlug("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void findOneReturnsPost() {
        var post = samplePost();
        when(repository.findById(1L)).thenReturn(Optional.of(post));
        when(sanitizer.sanitize(any())).thenReturn("<p>content</p>");

        var result = service.findOne(1L);
        assertThat(result.slug()).isEqualTo("test-slug");
    }

    @Test
    void findOneThrowsWhenNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findOne(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createSavesPostWithUniqueSlug() {
        var post = samplePost();
        var request = new PostRequest("new-slug", "New", "Excerpt", LocalDate.of(2026, 1, 1),
            3, "工程实践", List.of(), "#000", "02", false, PostStatus.DRAFT, "<p>new</p>");
        when(repository.existsBySlug("new-slug")).thenReturn(false);
        when(repository.save(any())).thenReturn(post);
        when(sanitizer.sanitize(any())).thenReturn("<p>new</p>");

        var result = service.create(request);
        assertThat(result).isNotNull();
    }

    @Test
    void createThrowsOnDuplicateSlug() {
        var request = new PostRequest("dup-slug", "Dup", "Excerpt", LocalDate.of(2026, 1, 1),
            3, "工程实践", List.of(), "#000", "02", false, PostStatus.DRAFT, "<p>dup</p>");
        when(repository.existsBySlug("dup-slug")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateSavesPostWithUniqueSlug() {
        var post = samplePost();
        var request = new PostRequest("test-slug", "Updated", "Excerpt", LocalDate.of(2026, 1, 1),
            5, "工程实践", List.of(), "#000", "01", false, PostStatus.PUBLISHED, "<p>updated</p>");
        when(repository.findById(1L)).thenReturn(Optional.of(post));
        when(repository.existsBySlugAndIdNot("test-slug", 1L)).thenReturn(false);
        when(sanitizer.sanitize(any())).thenReturn("<p>updated</p>");

        var result = service.update(1L, request);
        assertThat(result.title()).isEqualTo("Updated");
    }

    @Test
    void deleteRemovesExistingPost() {
        when(repository.existsById(1L)).thenReturn(true);
        service.delete(1L);
        verify(repository).deleteById(1L);
    }

    @Test
    void deleteThrowsWhenNotFound() {
        when(repository.existsById(99L)).thenReturn(false);
        assertThatThrownBy(() -> service.delete(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void likePostIncrementsCountAtomically() {
        var post = samplePost();
        post.setLikeCount(11); // 原子 UPDATE 之后重新读取到的值
        when(repository.incrementLikeCount("test-slug")).thenReturn(1);
        when(repository.findBySlugAndStatus("test-slug", PostStatus.PUBLISHED)).thenReturn(Optional.of(post));

        var result = service.likePost("test-slug");
        assertThat(result.slug()).isEqualTo("test-slug");
        assertThat(result.likeCount()).isEqualTo(11);
        verify(repository).incrementLikeCount("test-slug");
    }

    @Test
    void likePostThrowsWhenNotFound() {
        when(repository.incrementLikeCount("missing")).thenReturn(0);
        assertThatThrownBy(() -> service.likePost("missing")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getStatsReturnsCurrentCounts() {
        var post = samplePost();
        post.setLikeCount(7);
        post.setViewsCount(100);
        when(repository.findBySlugAndStatus("test-slug", PostStatus.PUBLISHED)).thenReturn(Optional.of(post));

        var result = service.getStats("test-slug");
        assertThat(result.slug()).isEqualTo("test-slug");
        assertThat(result.likeCount()).isEqualTo(7);
        assertThat(result.viewsCount()).isEqualTo(100);
    }

    @Test
    void getStatsThrowsWhenNotFound() {
        when(repository.findBySlugAndStatus("missing", PostStatus.PUBLISHED)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getStats("missing")).isInstanceOf(NotFoundException.class);
    }
}
