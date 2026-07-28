package com.yubai.blog.note;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;

/** NB-4：解码炸弹预检单测——头部宽高超限即拒，不给 ImageIO.read 吃内存的机会。 */
class NoteAttachmentDimensionTest {

    private static byte[] png(int width, int height) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * Generate a JPEG with specified width/height by creating a small valid JPEG
     * then patching the SOF0 marker with the desired dimensions.
     * This avoids allocating large BufferedImage pixel buffers.
     */
    private static byte[] jpegWithSize(int width, int height) throws IOException {
        var tiny = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        var out = new ByteArrayOutputStream();
        ImageIO.write(tiny, "jpg", out);
        var bytes = out.toByteArray();
        // Find SOF0 marker (0xFF 0xC0) and replace the 2-byte height/width after the 5-byte header
        for (int i = 0; i < bytes.length - 8; i++) {
            if ((bytes[i] & 0xFF) == 0xFF && (bytes[i + 1] & 0xFF) == 0xC0) {
                int len = ((bytes[i + 2] & 0xFF) << 8) | (bytes[i + 3] & 0xFF);
                if (len >= 9) { // SOF0 payload includes precision(1)+height(2)+width(2)+components
                    bytes[i + 5] = (byte)((height >> 8) & 0xFF);
                    bytes[i + 6] = (byte)(height & 0xFF);
                    bytes[i + 7] = (byte)((width >> 8) & 0xFF);
                    bytes[i + 8] = (byte)(width & 0xFF);
                    return bytes;
                }
            }
        }
        throw new RuntimeException("SOF0 marker not found in JPEG");
    }

    @Test
    void acceptsImageWithinLimit() throws IOException {
        assertThatCode(() -> NoteAttachmentService.assertDimensionsWithinLimit(png(64, 48)))
            .doesNotThrowAnyException();
    }

    @Test
    void rejectsOversizedWidth() throws IOException {
        byte[] wide = png(8001, 1);
        assertThatThrownBy(() -> NoteAttachmentService.assertDimensionsWithinLimit(wide))
            .isInstanceOf(InvalidNoteFileException.class)
            .hasMessageContaining("8000");
    }

    @Test
    void rejectsOversizedHeight() throws IOException {
        byte[] tall = png(1, 8001);
        assertThatThrownBy(() -> NoteAttachmentService.assertDimensionsWithinLimit(tall))
            .isInstanceOf(InvalidNoteFileException.class)
            .hasMessageContaining("8000");
    }

    @Test
    void rejectsUnreadableBytes() {
        byte[] garbage = "not an image at all".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> NoteAttachmentService.assertDimensionsWithinLimit(garbage))
            .isInstanceOf(InvalidNoteFileException.class);
    }

    @Test
    void rejectsExcessiveTotalPixels() throws IOException {
        // 5000 * 5000 = 25,000,000 > 20,000,000 — each dim under 8000 but total exceeds limit
        byte[] large = jpegWithSize(5000, 5000);
        assertThatThrownBy(() -> NoteAttachmentService.assertDimensionsWithinLimit(large))
            .isInstanceOf(InvalidNoteFileException.class)
            .hasMessageContaining("总像素");
    }

    @Test
    void acceptsTotalPixelsWithinLimit() throws IOException {
        // 4000 * 3000 = 12,000,000 < 20,000,000 — within limit
        byte[] ok = jpegWithSize(4000, 3000);
        assertThatCode(() -> NoteAttachmentService.assertDimensionsWithinLimit(ok))
            .doesNotThrowAnyException();
    }

    @Test
    void optimizerRejectsDecodeFailure() {
        byte[] corruptJpeg = new byte[]{(byte)0xFF, (byte)0xD8, (byte)0xFF, (byte)0xE0, 0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00};
        assertThatThrownBy(() -> NoteAttachmentService.optimizeImage(corruptJpeg, "image/jpeg"))
            .isInstanceOf(InvalidNoteFileException.class);
    }

    @Test
    void optimizerPassesNonJpegUnchanged() throws IOException {
        byte[] pngData = png(64, 48);
        byte[] result = NoteAttachmentService.optimizeImage(pngData, "image/png");
        assertThatCode(() -> NoteAttachmentService.assertDimensionsWithinLimit(result))
            .doesNotThrowAnyException();
    }
}
