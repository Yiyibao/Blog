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
}
