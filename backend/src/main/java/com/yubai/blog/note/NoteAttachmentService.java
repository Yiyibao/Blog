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
        try {
            return NoteAttachmentResponse.from(attachments.saveAndFlush(
                NoteAttachmentEntity.create(noteId, safeName.isBlank() ? "image" : safeName, type, optimizeImage(file.getBytes(), type))));
        } catch (IOException exception) {
            throw new InvalidNoteFileException("无法读取图片文件");
        }
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
