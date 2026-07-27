package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.ArrayList;
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
            5, "工程实践", List.of("tag1"), "#000000", "01", false, PostStatus.PUBLISHED, "<p>content</p>", null, null), sanitizer);
    }

    /** L-12：列表路径 stub 轻量投影行（标签由 findTagRows 批量补取，未 stub 时 Mockito 返回空列表即空标签）。 */
    private PostRepository.PostListRow sampleRow() {
        return new PostRepository.PostListRow() {
            @Override public Long getId() { return 1L; }
            @Override public String getSlug() { return "test-slug"; }
            @Override public String getTitle() { return "Test Title"; }
            @Override public String getExcerpt() { return "Excerpt"; }
            @Override public LocalDate getDate() { return LocalDate.of(2026, 1, 1); }
            @Override public int getReadTime() { return 5; }
            @Override public String getCategory() { return "工程实践"; }
            @Override public String getCategorySlug() { return "gongchengshijian"; }
            @Override public String getColor() { return "#000000"; }
            @Override public String getNumber() { return "01"; }
            @Override public boolean getFeatured() { return false; }
            @Override public PostStatus getStatus() { return PostStatus.PUBLISHED; }
            @Override public int getLikeCount() { return 0; }
            @Override public int getViewsCount() { return 0; }
        };
    }

    @Test
    void findPublishedDelegatesToRepository() {
        var rows = List.of(sampleRow());
        when(repository.findAllByStatusOrderByDateDesc(PostStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(rows));

        var result = service.findPublished(0, 10, null, null);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().get(0).slug()).isEqualTo("test-slug");
    }

    // L-12：列表标签经 findTagRows 一次 IN 查询补齐
    @Test
    void findPublishedAssemblesTagsFromBatchQuery() {
        var rows = List.of(sampleRow());
        when(repository.findAllByStatusOrderByDateDesc(PostStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(rows));
        when(repository.findTagRows(List.of(1L)))
            .thenReturn(List.<Object[]>of(new Object[]{1L, "tag1"}, new Object[]{1L, "tag2"}));

        var result = service.findPublished(0, 10, null, null);
        assertThat(result.items().get(0).tags()).containsExactly("tag1", "tag2");
    }

    // P1-2：sort=asc 走「最早优先」查询
    @Test
    void findPublishedSupportsAscendingSort() {
        var rows = List.of(sampleRow());
        when(repository.findAllByStatusOrderByDateAsc(PostStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(rows));

        var result = service.findPublished(0, 10, "", "asc");
        assertThat(result.items()).hasSize(1);
    }

    // P1-2：categorySlug 非空时走分类过滤查询
    @Test
    void findPublishedSupportsCategoryFilter() {
        var rows = List.of(sampleRow());
        when(repository.findByCategorySlugAndStatusOrderByDateDesc("engineering", PostStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(rows));

        var result = service.findPublished(0, 10, "engineering", "desc");
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findPublishedSupportsCategoryFilterWithAscendingSort() {
        var rows = List.of(sampleRow());
        when(repository.findByCategorySlugAndStatusOrderByDateAsc("engineering", PostStatus.PUBLISHED, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(rows));

        var result = service.findPublished(0, 10, "engineering", "asc");
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findAdminWithoutStatusReturnsAll() {
        var rows = List.of(sampleRow());
        when(repository.findAllByOrderByDateDesc(any(Pageable.class))).thenReturn(new PageImpl<>(rows));

        var result = service.findAdmin(null, 0, 10);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findAdminWithStatusFiltersByStatus() {
        var rows = List.of(sampleRow());
        when(repository.findAllByStatusOrderByDateDesc(PostStatus.DRAFT, PageRequests.of(0, 10)))
            .thenReturn(new PageImpl<>(rows));

        var result = service.findAdmin(PostStatus.DRAFT, 0, 10);
        assertThat(result.items()).hasSize(1);
    }

    @Test
    void findPublishedBySlugReturnsPost() {
        var post = samplePost();
        when(repository.findBySlugAndStatus("test-slug", PostStatus.PUBLISHED)).thenReturn(Optional.of(post));

        var result = service.findPublishedBySlug("test-slug");
        assertThat(result.slug()).isEqualTo("test-slug");
    }

    /** P1-8：浏览量走数据库端原子自增（与 likeCount 同一并发安全机制）。 */
    @Test
    void registerViewDelegatesToAtomicIncrement() {
        when(repository.incrementViewsCount("test-slug")).thenReturn(1);

        assertThat(service.registerView("test-slug")).isEqualTo(1);
        verify(repository).incrementViewsCount("test-slug");
    }

    /** P1-3：写入已消毒，读路径必须直接返回存储值、零消毒调用（消除每次详情读的整篇 jsoup 遍历）。 */
    @Test
    void readPathReturnsStoredContentWithoutResanitizing() {
        when(sanitizer.sanitize("<p>content</p>")).thenReturn("<p>stored-clean</p>");
        var post = samplePost();
        clearInvocations(sanitizer);
        when(repository.findBySlugAndStatus("test-slug", PostStatus.PUBLISHED)).thenReturn(Optional.of(post));

        var result = service.findPublishedBySlug("test-slug");

        assertThat(result.content()).isEqualTo("<p>stored-clean</p>");
        verifyNoInteractions(sanitizer);
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
            3, "工程实践", List.of(), "#000", "02", false, PostStatus.DRAFT, "<p>new</p>", null, null);
        when(repository.existsBySlug("new-slug")).thenReturn(false);
        when(repository.save(any())).thenReturn(post);
        when(sanitizer.sanitize(any())).thenReturn("<p>new</p>");

        var result = service.create(request);
        assertThat(result).isNotNull();
    }

    @Test
    void createThrowsOnDuplicateSlug() {
        var request = new PostRequest("dup-slug", "Dup", "Excerpt", LocalDate.of(2026, 1, 1),
            3, "工程实践", List.of(), "#000", "02", false, PostStatus.DRAFT, "<p>dup</p>", null, null);
        when(repository.existsBySlug("dup-slug")).thenReturn(true);

        assertThatThrownBy(() -> service.create(request)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updateSavesPostWithUniqueSlug() {
        var post = samplePost();
        var request = new PostRequest("test-slug", "Updated", "Excerpt", LocalDate.of(2026, 1, 1),
            5, "工程实践", List.of(), "#000", "01", false, PostStatus.PUBLISHED, "<p>updated</p>", null, null);
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

    private PostRepository.PostListRow sampleRow2() {
        return new PostRepository.PostListRow() {
            @Override public Long getId() { return 2L; }
            @Override public String getSlug() { return "related-post"; }
            @Override public String getTitle() { return "Related Post"; }
            @Override public String getExcerpt() { return "Related excerpt"; }
            @Override public LocalDate getDate() { return LocalDate.of(2026, 2, 1); }
            @Override public int getReadTime() { return 3; }
            @Override public String getCategory() { return "工程实践"; }
            @Override public String getCategorySlug() { return "工程实践"; }
            @Override public String getColor() { return "#ff0000"; }
            @Override public String getNumber() { return "02"; }
            @Override public boolean getFeatured() { return false; }
            @Override public PostStatus getStatus() { return PostStatus.PUBLISHED; }
            @Override public int getLikeCount() { return 0; }
            @Override public int getViewsCount() { return 0; }
        };
    }

    @Test
    void findRelatedPostsReturnsTopNBySharedTags() {
        when(repository.findRelatedPostIdsByTagMatch(1L, List.of("tag1", "tag2"), PageRequests.of(0, 4)))
            .thenReturn(List.of(2L));
        when(repository.findRowsByIds(List.of(2L))).thenReturn(new ArrayList<>(List.of(sampleRow2())));
        when(repository.findTagRows(List.of(2L)))
            .thenReturn(List.<Object[]>of(new Object[]{2L, "tag1"}, new Object[]{2L, "tag2"}));

        var result = service.findRelatedPosts(1L, List.of("tag1", "tag2"), "工程实践", 4);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).slug()).isEqualTo("related-post");
        assertThat(result.get(0).tags()).containsExactly("tag1", "tag2");
    }

    @Test
    void findRelatedPostsFallsBackToSameCategoryWhenNoSharedTags() {
        when(repository.findRelatedPostIdsByTagMatch(1L, List.of("unique-tag"), PageRequests.of(0, 4)))
            .thenReturn(List.of());
        when(repository.findByCategorySlugAndStatusAndIdNotOrderByDateDesc("工程实践", PostStatus.PUBLISHED, 1L, PageRequests.of(0, 4)))
            .thenReturn(new PageImpl<>(List.of(sampleRow2())));
        when(repository.findTagRows(List.of(2L)))
            .thenReturn(List.<Object[]>of(new Object[]{2L, "tag-a"}, new Object[]{2L, "tag-b"}));

        var result = service.findRelatedPosts(1L, List.of("unique-tag"), "工程实践", 4);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).category()).isEqualTo("工程实践");
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
