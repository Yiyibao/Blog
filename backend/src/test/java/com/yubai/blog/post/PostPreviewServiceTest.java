package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.yubai.blog.common.NotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class PostPreviewServiceTest {
    private final PostRepository posts = mock(PostRepository.class);
    private final PostPreviewTokenRepository tokens = mock(PostPreviewTokenRepository.class);
    private final Instant now = Instant.parse("2026-08-13T08:00:00Z");
    private final PostPreviewService service =
            new PostPreviewService(posts, tokens, Clock.fixed(now, ZoneOffset.UTC));
    private PostEntity post;

    @BeforeEach
    void setUp() {
        post =
                PostEntity.create(
                        new PostRequest(
                                "preview",
                                "Preview article",
                                "A preview summary",
                                java.time.LocalDate.of(2026, 8, 13),
                                3,
                                "工程实践",
                                java.util.List.of("preview"),
                                "#123456",
                                "01",
                                false,
                                PostStatus.DRAFT,
                                "<p>draft</p>",
                                null,
                                ContentFormat.HTML),
                        "preview",
                        new PostContentSanitizer());
        ReflectionTestUtils.setField(post, "id", 41L);
        when(posts.findById(41L)).thenReturn(Optional.of(post));
        when(tokens.save(any(PostPreviewTokenEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void tokenIsRandomAndResolvesOnlyForCurrentVersion() {
        var created = service.create(41L, 10, "admin");
        assertThat(created.token()).isNotBlank();
        assertThat(created.token()).doesNotContain("<", ">", " ");

        PostPreviewTokenEntity saved =
                PostPreviewTokenEntity.create(
                        41L,
                        sha256(created.token()),
                        created.postVersion(),
                        created.expiresAt(),
                        "admin");
        ReflectionTestUtils.setField(saved, "id", created.id());
        when(tokens.findByTokenHash(sha256(created.token()))).thenReturn(Optional.of(saved));
        assertThat(service.resolve(41L, created.token()).slug()).isEqualTo("preview");

        ReflectionTestUtils.setField(post, "version", 1L);
        assertThatThrownBy(() -> service.resolve(41L, created.token()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void guessingAndRevocationAreIndistinguishableFromMissingToken() {
        assertThatThrownBy(() -> service.resolve(41L, "guess"))
                .isInstanceOf(NotFoundException.class);

        var created = service.create(41L, 10, "admin");
        PostPreviewTokenEntity saved =
                PostPreviewTokenEntity.create(
                        41L,
                        sha256(created.token()),
                        created.postVersion(),
                        created.expiresAt(),
                        "admin");
        ReflectionTestUtils.setField(saved, "id", created.id());
        when(tokens.findByIdAndPostId(created.id(), 41L)).thenReturn(Optional.of(saved));
        when(tokens.findByTokenHash(sha256(created.token()))).thenReturn(Optional.of(saved));
        service.revoke(41L, created.id());

        assertThatThrownBy(() -> service.resolve(41L, created.token()))
                .isInstanceOf(NotFoundException.class);
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of()
                    .formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            value.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }
}
