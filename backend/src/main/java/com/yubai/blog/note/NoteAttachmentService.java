package com.yubai.blog.note;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.storage.StorageException;
import com.yubai.blog.storage.StorageService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
@Transactional(readOnly = true)
public class NoteAttachmentService {
    private static final Logger log = LoggerFactory.getLogger(NoteAttachmentService.class);
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    private static final Set<String> SAFE_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final int MAX_WIDTH = 1920;
    private static final int MAX_PIXEL_DIMENSION = 8000;
    private static final long MAX_TOTAL_PIXELS = 20_000_000L;
    private static final float JPEG_QUALITY = 0.85f;
    private final NoteAttachmentRepository attachments;
    private final NoteRepository notes;
    private final StorageService storageService;
    private final long maxTotalBytes;

    @Autowired
    public NoteAttachmentService(
            NoteAttachmentRepository attachments,
            NoteRepository notes,
            StorageService storageService,
            @Value("${app.attachment.max-total-bytes:1073741824}") long maxTotalBytes) {
        this.attachments = attachments;
        this.notes = notes;
        this.storageService = storageService;
        this.maxTotalBytes = maxTotalBytes;
    }

    public NoteAttachmentService(
            NoteAttachmentRepository attachments,
            NoteRepository notes,
            StorageService storageService) {
        this(attachments, notes, storageService, 1024L * 1024 * 1024);
    }

    public List<NoteAttachmentResponse> findForNote(long noteId) {
        requireNote(noteId);
        return attachments.findListRowsByNoteId(noteId).stream()
                .map(NoteAttachmentResponse::from)
                .toList();
    }

    public NoteAttachmentEntity findForNote(long noteId, long attachmentId) {
        requireNote(noteId);
        var attachment =
                attachments
                        .findByIdAndDeletedAtIsNull(attachmentId)
                        .orElseThrow(() -> new NotFoundException("图片不存在"));
        if (attachment.getNoteId() != noteId) throw new NotFoundException("图片不存在");
        return attachment;
    }

    public NoteAttachmentEntity findPublic(UUID publicId) {
        var attachment =
                attachments
                        .findByPublicIdAndDeletedAtIsNull(publicId)
                        .orElseThrow(() -> new NotFoundException("图片不存在"));
        var note =
                notes.findById(attachment.getNoteId())
                        .orElseThrow(() -> new NotFoundException("图片不存在"));
        if (note.getStatus() != NoteStatus.PUBLISHED) throw new NotFoundException("图片不存在");
        return attachment;
    }

    /** 6B：以读写事务获取附件内容——storage_key 优先，bytea 回退并惰性迁移。 */
    @Transactional
    public byte[] readContent(long noteId, long attachmentId) {
        var attachment = findForNote(noteId, attachmentId);
        return resolveContent(attachment);
    }

    @Transactional
    public byte[] readPublicContent(UUID publicId) {
        var attachment = findPublic(publicId);
        return resolveContent(attachment);
    }

    private byte[] resolveContent(NoteAttachmentEntity attachment) {
        if (attachment.getStorageKey() != null) {
            return storageService.read(attachment.getStorageKey());
        }
        if (attachment.getContent() != null) {
            lazyMigrate(attachment);
            return attachment.getContent();
        }
        throw new StorageException(
                "Corrupt attachment data: both storage_key and content are null for id="
                        + attachment.getId());
    }

    private void lazyMigrate(NoteAttachmentEntity attachment) {
        var key = attachment.getPublicId() + "/legacy-" + UUID.randomUUID();
        try {
            storageService.store(key, attachment.getContent());
        } catch (Exception e) {
            log.warn(
                    "Lazy migration store failed for attachment {}, keeping bytea: {}",
                    attachment.getId(),
                    e.toString());
            return;
        }
        var rollbackCleanupRegistered = registerRollbackCleanup(key);
        try {
            if (attachments.claimStorageKey(attachment.getId(), key) == 1) {
                log.info("Lazy-migrated attachment {} to storage key {}", attachment.getId(), key);
            } else {
                deleteQuietly(key);
            }
        } catch (Exception e) {
            if (!rollbackCleanupRegistered) deleteQuietly(key);
            throw e;
        }
    }

    @Transactional
    public NoteAttachmentResponse upload(long noteId, MultipartFile file) {
        requireNote(noteId);
        var type = file.getContentType() == null ? "" : file.getContentType().toLowerCase();
        if (file.isEmpty() || file.getSize() > MAX_BYTES)
            throw new InvalidNoteFileException("图片不能为空且不能超过 8 MB");
        if (!SAFE_IMAGE_TYPES.contains(type))
            throw new InvalidNoteFileException("只支持 PNG、JPEG、WebP 或 GIF 图片");
        var aggregate = attachments.aggregateStorage();
        if (aggregate != null && aggregate.getBytes() + file.getSize() > maxTotalBytes) {
            throw new InvalidNoteFileException("附件空间已达到容量上限，请清理回收站后重试");
        }
        var original = file.getOriginalFilename() == null ? "image" : file.getOriginalFilename();
        var safeName = NoteService.safeFilename(original);
        byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException exception) {
            throw new InvalidNoteFileException("无法读取图片文件");
        }
        if (!matchesMagicBytes(data, type)) {
            throw new InvalidNoteFileException("图片内容与声明的类型不符");
        }
        assertDimensionsWithinLimit(data);
        data = optimizeImage(data, type);

        var name = safeName.isBlank() ? "image" : safeName;
        var entity = NoteAttachmentEntity.createWithStorage(noteId, name, type, data.length, null);
        var ext =
                switch (type) {
                    case "image/jpeg" -> ".jpg";
                    case "image/png" -> ".png";
                    case "image/webp" -> ".webp";
                    case "image/gif" -> ".gif";
                    default -> "";
                };
        var key = entity.getPublicId() + "/image" + ext;
        storageService.store(key, data);
        entity.setStorageKey(key);
        var rollbackCleanupRegistered = registerRollbackCleanup(key);
        try {
            entity = attachments.saveAndFlush(entity);
        } catch (Exception e) {
            if (!rollbackCleanupRegistered) deleteQuietly(key);
            throw e;
        }
        return NoteAttachmentResponse.from(entity);
    }

    public static boolean matchesMagicBytes(byte[] data, String mimeType) {
        return switch (mimeType) {
            case "image/png" ->
                    startsWith(data, new int[] {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "image/jpeg" -> startsWith(data, new int[] {0xFF, 0xD8, 0xFF});
            case "image/gif" ->
                    startsWith(data, new int[] {'G', 'I', 'F', '8', '7', 'a'})
                            || startsWith(data, new int[] {'G', 'I', 'F', '8', '9', 'a'});
            case "image/webp" ->
                    data.length >= 12
                            && startsWith(data, new int[] {'R', 'I', 'F', 'F'})
                            && data[8] == 'W'
                            && data[9] == 'E'
                            && data[10] == 'B'
                            && data[11] == 'P';
            default -> false;
        };
    }

    public static void assertDimensionsWithinLimit(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(data))) {
            Iterator<javax.imageio.ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidNoteFileException("无法识别的图片内容");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width > MAX_PIXEL_DIMENSION || height > MAX_PIXEL_DIMENSION) {
                    throw new InvalidNoteFileException(
                            "图片尺寸不能超过 " + MAX_PIXEL_DIMENSION + "×" + MAX_PIXEL_DIMENSION + " 像素");
                }
                if ((long) width * height > MAX_TOTAL_PIXELS) {
                    throw new InvalidNoteFileException(
                            "图片总像素不能超过 "
                                    + MAX_TOTAL_PIXELS
                                    + "（当前 "
                                    + ((long) width * height)
                                    + "）");
                }
            } finally {
                reader.dispose();
            }
        } catch (InvalidNoteFileException e) {
            throw e;
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
            if (image == null) throw new InvalidNoteFileException("JPEG 解码失败：ImageIO.read 返回 null");
            int w = image.getWidth();
            int h = image.getHeight();
            if (w > MAX_WIDTH) {
                h = h * MAX_WIDTH / w;
                w = MAX_WIDTH;
            }
            var scaled =
                    new java.awt.image.BufferedImage(
                            w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g = scaled.createGraphics();
            g.drawImage(image, 0, 0, w, h, null);
            g.dispose();
            var out = new ByteArrayOutputStream();
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
            if (!writers.hasNext()) throw new InvalidNoteFileException("没有可用的 JPEG 编码器");
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
        } catch (InvalidNoteFileException e) {
            throw e;
        } catch (Exception e) {
            throw new InvalidNoteFileException("图片优化失败：" + e.getMessage());
        }
    }

    @Transactional
    public void delete(long noteId, long attachmentId) {
        var attachment = findForNote(noteId, attachmentId);
        attachment.moveToTrash();
        attachments.save(attachment);
    }

    @Transactional
    public NoteAttachmentResponse restore(long noteId, long attachmentId) {
        requireNote(noteId);
        var attachment =
                attachments
                        .findById(attachmentId)
                        .orElseThrow(() -> new NotFoundException("图片不存在"));
        if (attachment.getNoteId() != noteId || attachment.getDeletedAt() == null)
            throw new NotFoundException("图片不存在");
        attachment.restore();
        return NoteAttachmentResponse.from(attachments.save(attachment));
    }

    /** Permanently removes recycle-bin entries after the configured 30-day recovery window. */
    @Transactional
    @Scheduled(cron = "${app.attachment.trash-cleanup-cron:0 20 3 * * *}", zone = "Asia/Shanghai")
    public void purgeExpiredTrash() {
        for (var attachment :
                attachments.findByDeletedAtBefore(
                        java.time.Instant.now().minus(java.time.Duration.ofDays(30)))) {
            var storageKey = attachment.getStorageKey();
            attachments.delete(attachment);
            if (storageKey != null) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void afterCommit() {
                                deleteQuietly(storageKey);
                            }
                        });
            }
        }
    }

    private boolean registerRollbackCleanup(String storageKey) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return false;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        if (status != STATUS_COMMITTED) deleteQuietly(storageKey);
                    }
                });
        return true;
    }

    private void deleteQuietly(String storageKey) {
        try {
            storageService.delete(storageKey);
        } catch (Exception e) {
            log.warn("Failed to clean up storage file {}: {}", storageKey, e.toString());
        }
    }

    private void requireNote(long noteId) {
        if (!notes.existsById(noteId)) throw new NotFoundException("笔记不存在：" + noteId);
    }
}
