package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class PostWorkflowServiceTest {
    private final PostRepository repository = mock(PostRepository.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final Instant now = Instant.parse("2027-01-15T08:00:00Z");
    private final PostWorkflowService service =
            new PostWorkflowService(repository, jdbcTemplate, Clock.fixed(now, ZoneOffset.UTC));
    private PostEntity post;

    @BeforeEach
    void setUp() {
        var request =
                new PostRequest(
                        "workflow",
                        "Workflow",
                        "excerpt",
                        LocalDate.now(),
                        3,
                        "工程实践",
                        List.of("initial"),
                        "#123456",
                        "01",
                        false,
                        PostStatus.DRAFT,
                        "<p>content</p>",
                        null,
                        ContentFormat.HTML);
        post = PostEntity.create(request, "workflow", new PostContentSanitizer());
        ReflectionTestUtils.setField(post, "id", 9L);
        when(repository.findById(9L)).thenReturn(Optional.of(post));
    }

    @Test
    void schedulesAndPublishesDuePost() {
        var publishAt = Instant.now().plusSeconds(300);
        var scheduled = service.schedule(9L, publishAt, "admin");
        assertThat(scheduled.status()).isEqualTo(PostStatus.DRAFT);
        assertThat(scheduled.scheduledPublishAt()).isEqualTo(publishAt);

        when(jdbcTemplate.queryForList(anyString(), any(Class.class), any()))
                .thenReturn(List.of(9L), List.of());
        service.publishDue();
        service.publishDue();

        verify(jdbcTemplate, times(2)).queryForList(anyString(), any(Class.class), any());
        verify(jdbcTemplate, times(1)).update(anyString(), any(), any(), any(), any());
    }

    @Test
    void batchAddsTagsAndArchives() {
        when(repository.findAllById(List.of(9L))).thenReturn(List.of(post));

        service.batch(
                new PostWorkflowService.BatchRequest(
                        List.of(9L), PostWorkflowService.BatchAction.ADD_TAGS, List.of("release")),
                "admin");
        assertThat(post.getTags()).containsExactly("initial", "release");

        service.batch(
                new PostWorkflowService.BatchRequest(
                        List.of(9L), PostWorkflowService.BatchAction.ARCHIVE, List.of()),
                "admin");
        assertThat(post.getStatus()).isEqualTo(PostStatus.ARCHIVED);
    }
}
