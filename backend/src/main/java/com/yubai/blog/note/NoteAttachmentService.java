package com.yubai.blog.note;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.yubai.blog.common.NotFoundException;

@Service
@Transactional(readOnly = true)
public class NoteAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(NoteAttachmentService.class);
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final Set<String> SAFE_IMAGE_TYPES = Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_PIXEL_DIMENSION = 8000;
    private static final float JPEG_QUALITY = 0.85f;
    private final NoteAttachmentRepository attachments;
    private final NoteRepository notes;

    public NoteAttachmentService(NoteAttachmentRepository attachments, NoteRepository notes) {
        this.attachments = attachments;
        this.notes = notes;
    }

    public List<NoteAttachmentResponse> findForNote(long noteId) {
        requireNote(noteId);
        return attachments.findAllByNoteIdOrderByCreatedAtDesc(noteId).stream().map(NoteAttachmentResponse::from).toList();
    }

    public NoteAttachmentEntity findForNote(long noteId, long attachmentId) {
        requireNote(noteId);
        var attachment = attachments.findById(attachmentId).orElseThrow(() -> new NotFoundException("图片不存在"));
        if (attachment.getNoteId() != noteId) throw new NotFoundException("图片不存在");
        return attachment;
    }

    public NoteAttachmentEntity findPublic(UUID publicId) {
        var attachment = attachments.findByPublicId(publicId).orElseThrow(() -> new NotFoundException("图片不存在"));
        var note = notes.findById(attachment.getNoteId()).orElseThrow(() -> new NotFoundException("图片不存在"));
        if (note.getStatus() != NoteStatus.PUBLISHED) throw new NotFoundException("图片不存在");
        return attachment;
    }

    @Transactional
    public NoteAttachmentResponse upload(long noteId, MultipartFile file) {
        requireNote(noteId);
        var type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (file.isEmpty() || file.getSize() > MAX_BYTES) throw new InvalidNoteFileException("图片不能为空且不能超过 8 MB");
        if (!SAFE_IMAGE_TYPES.contains(type)) throw new InvalidNoteFileException("只支持 PNG、JPEG、WebP 或 GIF 图片");
        var original = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        var safeName = NoteService.safeFilename(original);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException exception) {
            throw new InvalidNoteFileException("无法读取图片文件");
        }
        // P0-6：不信任客户端 Content-Type，按文件头 magic bytes 校验实际格式
        if (!matchesMagicBytes(data, type)) {
            throw new InvalidNoteFileException("图片内容与声明的类型不符");
        }
        // NB-4：解码炸弹预检——8MB 的高压缩比图可膨胀成 GB 级像素，先读头部宽高再允许解码
        assertDimensionsWithinLimit(data);
        return NoteAttachmentResponse.from(attachments.saveAndFlush(
            NoteAttachmentEntity.create(noteId, safeName.isBlank() ? "image" : safeName, type, optimizeImage(data, type))));
    }

    /** P0-6：PNG/JPEG/WebP/GIF 文件头嗅探。 */
    static boolean matchesMagicBytes(byte[] data, String mimeType) {
        return switch (mimeType) {
            case "image/png" -> startsWith(data, new int[]{0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/jpeg" -> startsWith(data, new int[]{0xFF, 0xD8, 0xFF});
            case "image/gif" -> startsWith(data, new int[]{'G', 'I', 'F', '8', '7', 'a'})
                || startsWith(data, new int[]{'G', 'I', 'F', '8', '9', 'a'});
            case "image/webp" -> data.length >= 12
                && startsWith(data, new int[]{'R', 'I', 'F', 'F'})
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P';
            default -> false;
        };
    }

    /** NB-4：只解析图片头取宽高（不解码像素），超过 8000×8000 直接拒绝。 */
    static void assertDimensionsWithinLimit(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidNoteFileException("无法识别的图片内容");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_PIXEL_DIMENSION || height > MAX_PIXEL_DIMENSION) {
                    throw new InvalidNoteFileException("图片尺寸不能超过 " + MAX_PIXEL_DIMENSION + "×" + MAX_PIXEL_DIMENSION + " 像素");
                }
            } finally {
                reader.dispose();
            }
        } catch (IOException exception) {
            throw new InvalidNoteFileException("无法读取图片文件");
        }
    }

    private static boolean startsWith(byte[] data, int[] prefix) {
        if (data.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != prefix[i]) return false;
        }
        return true;
    }

    static byte[] optimizeImage(byte[] data, String mimeType) {
        if (!mimeType.equals("image/jpeg")) return data;
        try {
            var image = ImageIO.read(new java.io.ByteArrayInputStream(data));
            if (image == null) return data;
            int w = image.getWidth();
            int h = image.getHeight();
            if (w > MAX_WIDTH) {
                h = h * MAX_WIDTH / w;
                w = MAX_WIDTH;
            }
            var scaled = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = scaled.createGraphics();
            g.drawImage(image, 0, 0, w, h, null);
            g.dispose();
            var out = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) return data;
            try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                ImageWriter writer = writers.next();
                ImageWriteParam param = writer.getDefaultWriteParam();
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
                writer.setOutput(ios);
                writer.write(null, new IIOImage(scaled, null, null), param);
                writer.dispose();
            }
            return out.toByteArray();
        } catch (Exception e) {
            log.warn("Image optimization failed, storing original: {}", e.toString());
            return data;
        }
    }

    @Transactional
    public void delete(long noteId, long attachmentId) {
        attachments.delete(findForNote(noteId, attachmentId));
    }

    private void requireNote(long noteId) {
        if (!notes.existsById(noteId)) throw new NotFoundException("笔记不存在：" + noteId);
    }
}
