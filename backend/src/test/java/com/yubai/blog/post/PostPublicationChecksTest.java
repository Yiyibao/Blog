package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PostPublicationChecksTest {
    private final PostContentSanitizer sanitizer = new PostContentSanitizer();

    @Test
    void rejectsMissingSummaryAndEmptyImageAlt() {
        var post = post("<p>body</p><img src=\"https://example.com/a.png\" alt=\"\">");
        ReflectionTestUtils.setField(post, "excerpt", "");
        var request = PostPublicationChecks.evaluate(post, null);

        assertThat(request.publishable()).isFalse();
        assertThat(request.checks())
                .extracting(PostPublicationChecks.Check::code)
                .contains("SUMMARY_REQUIRED", "IMAGE_ALT_REQUIRED", "COVER_ALT_REQUIRED");
    }

    @Test
    void rejectsBrokenLinksAndPastSchedule() {
        var post = post("<p>body</p><a href=\"\">broken</a>");
        var result = PostPublicationChecks.evaluate(post, Instant.now().minusSeconds(1));

        assertThat(result.publishable()).isFalse();
        assertThat(result.checks())
                .extracting(PostPublicationChecks.Check::code)
                .contains("SCHEDULE_IN_PAST", "BROKEN_LINK_MARKUP");
        assertThatThrownBy(() -> PostPublicationChecks.requirePublishable(post, null))
                .isInstanceOf(PostPublicationException.class);
    }

    @Test
    void reportsSeoLengthWarningsWithoutBlockingPublication() {
        var post = post("<p>body</p>");
        ReflectionTestUtils.setField(post, "title", "x".repeat(71));
        ReflectionTestUtils.setField(post, "excerpt", "x".repeat(161));

        var result = PostPublicationChecks.evaluate(post, null);

        assertThat(result.publishable()).isTrue();
        assertThat(result.checks())
                .extracting(PostPublicationChecks.Check::code)
                .contains("SEO_TITLE_LONG", "SEO_DESCRIPTION_LONG");
    }

    private PostEntity post(String content) {
        return PostEntity.create(
                new PostRequest(
                        "publication-checks",
                        "A sufficiently descriptive title",
                        "A sufficiently descriptive summary for search engines",
                        LocalDate.of(2026, 8, 13),
                        5,
                        "工程实践",
                        List.of("quality"),
                        "#123456",
                        "01",
                        false,
                        PostStatus.DRAFT,
                        content,
                        null,
                        ContentFormat.HTML),
                "publication-checks",
                sanitizer);
    }
}
