package com.yubai.blog.note;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/** P0-6：附件 magic-byte 嗅探单测。 */
class NoteAttachmentMagicBytesTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0};
    private static final byte[] GIF89 = "GIF89a....".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] GIF87 = "GIF87a....".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] WEBP = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P', 0};

    @Test
    void acceptsMatchingSignatures() {
        assertThat(NoteAttachmentService.matchesMagicBytes(PNG, "image/png")).isTrue();
        assertThat(NoteAttachmentService.matchesMagicBytes(JPEG, "image/jpeg")).isTrue();
        assertThat(NoteAttachmentService.matchesMagicBytes(GIF89, "image/gif")).isTrue();
        assertThat(NoteAttachmentService.matchesMagicBytes(GIF87, "image/gif")).isTrue();
        assertThat(NoteAttachmentService.matchesMagicBytes(WEBP, "image/webp")).isTrue();
    }

    @Test
    void rejectsForgedContentType() {
        var html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        assertThat(NoteAttachmentService.matchesMagicBytes(html, "image/png")).isFalse();
        assertThat(NoteAttachmentService.matchesMagicBytes(html, "image/jpeg")).isFalse();
        assertThat(NoteAttachmentService.matchesMagicBytes(html, "image/gif")).isFalse();
        assertThat(NoteAttachmentService.matchesMagicBytes(html, "image/webp")).isFalse();
    }

    @Test
    void rejectsCrossTypeMismatch() {
        assertThat(NoteAttachmentService.matchesMagicBytes(PNG, "image/jpeg")).isFalse();
        assertThat(NoteAttachmentService.matchesMagicBytes(JPEG, "image/png")).isFalse();
        // RIFF 容器但不是 WEBP（如 WAV）
        byte[] wav = {'R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'A', 'V', 'E', 0};
        assertThat(NoteAttachmentService.matchesMagicBytes(wav, "image/webp")).isFalse();
    }

    @Test
    void rejectsUnknownTypeAndShortData() {
        assertThat(NoteAttachmentService.matchesMagicBytes(PNG, "image/svg+xml")).isFalse();
        assertThat(NoteAttachmentService.matchesMagicBytes(new byte[] {(byte) 0x89}, "image/png")).isFalse();
        assertThat(NoteAttachmentService.matchesMagicBytes(new byte[0], "image/gif")).isFalse();
    }
}
