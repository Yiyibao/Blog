package com.yubai.blog.dish;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.note.InvalidNoteFileException;
import com.yubai.blog.note.NoteAttachmentService;
import com.yubai.blog.storage.StorageException;
import com.yubai.blog.storage.StorageService;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DishAssetService {
    private static final long MAX_BYTES = 8L * 1024 * 1024;
    static final long MAX_STAGED_BYTES_PER_OWNER = 64L * 1024 * 1024;
    static final long MAX_STAGED_COUNT_PER_OWNER = 20;
    private static final Set<String> SAFE_IMAGE_TYPES =
            Set.of("image/png", "image/jpeg", "image/webp", "image/gif");
    private static final Logger log = LoggerFactory.getLogger(DishAssetService.class);

    private final DishAssetRepository repository;
    private final StorageService storageService;

    public DishAssetService(DishAssetRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    public DishAssetEntity findByPublicId(UUID publicId) {
        return repository
                .findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("图片不存在"));
    }

    public DishAssetEntity findByDishId(long dishId) {
        return repository.findByDishId(dishId).orElseThrow(() -> new NotFoundException("图片不存在"));
    }

    public byte[] readContent(UUID publicId) {
        var asset = findByPublicId(publicId);
        if (asset.getContent() != null) return asset.getContent();
        if (asset.getStorageKey() != null) return storageService.read(asset.getStorageKey());
        throw new StorageException("Dish asset has no content: " + publicId);
    }

    /** Uploads a staged image directly into PostgreSQL; it is attached after the dish is saved. */
    @Transactional
    public DishAssetResponse uploadStaged(MultipartFile file) {
        return uploadStaged(file, "admin");
    }

    @Transactional
    public DishAssetResponse uploadStaged(MultipartFile file, String owner) {
        var normalizedOwner = requireOwner(owner);
        var mediaType = normalizeMediaType(file.getContentType());
        if (file.isEmpty() || file.getSize() > MAX_BYTES) {
            throw new InvalidNoteFileException("图片不能为空且不能超过 8 MB");
        }
        if (!SAFE_IMAGE_TYPES.contains(mediaType)) {
            throw new InvalidNoteFileException("只支持 PNG、JPEG、WebP 或 GIF 图片");
        }

        final byte[] data;
        try {
            data = file.getBytes();
        } catch (IOException exception) {
            throw new InvalidNoteFileException("无法读取图片文件");
        }
        if (data.length == 0 || data.length > MAX_BYTES) {
            throw new InvalidNoteFileException("图片不能为空且不能超过 8 MB");
        }
        if (!NoteAttachmentService.matchesMagicBytes(data, mediaType)) {
            throw new InvalidNoteFileException("图片内容与声明的类型不符");
        }
        NoteAttachmentService.assertDimensionsWithinLimit(data);
        repository.lockOwnerQuota(normalizedOwner);
        if (repository.countByOwnerAndDishIdIsNull(normalizedOwner) >= MAX_STAGED_COUNT_PER_OWNER
                || repository.sumStagedBytes(normalizedOwner) + data.length
                        > MAX_STAGED_BYTES_PER_OWNER) {
            throw new InvalidNoteFileException("Staged dish asset quota exceeded");
        }
        var dimensions = readDimensions(data);
        var entity =
                DishAssetEntity.createWithContent(
                        normalizedOwner,
                        safeFilename(file.getOriginalFilename()),
                        mediaType,
                        data,
                        sha256hex(data),
                        dimensions[0],
                        dimensions[1]);
        return DishAssetResponse.from(repository.saveAndFlush(entity));
    }

    public DishAssetEntity createForDish(
            long dishId,
            String storageKey,
            String fileName,
            String mediaType,
            byte[] data,
            Integer width,
            Integer height) {
        storageService.store(storageKey, data);
        var sha256 = sha256hex(data);
        var asset =
                DishAssetEntity.create(
                        "admin",
                        storageKey,
                        fileName,
                        mediaType,
                        data.length,
                        sha256,
                        width,
                        height);
        asset.setDishId(dishId);
        return repository.save(asset);
    }

    public DishAssetEntity createStaged(
            String storageKey,
            String fileName,
            String mediaType,
            byte[] data,
            Integer width,
            Integer height) {
        var sha256 = sha256hex(data);
        var asset =
                DishAssetEntity.create(
                        "admin",
                        storageKey,
                        fileName,
                        mediaType,
                        data.length,
                        sha256,
                        width,
                        height);
        return repository.save(asset);
    }

    @Transactional
    public void assignToDish(long assetId, long dishId) {
        var asset = repository.findById(assetId).orElseThrow(() -> new NotFoundException("图片不存在"));
        attachToDish(asset, dishId);
    }

    @Transactional
    public void assignToDish(UUID publicId, long dishId) {
        var asset =
                repository
                        .findByPublicId(publicId)
                        .orElseThrow(() -> new NotFoundException("Dish asset does not exist"));
        attachToDish(asset, dishId);
    }

    @Transactional
    public void assignToDish(UUID publicId, long dishId, String owner) {
        var asset =
                repository
                        .findByPublicIdAndOwner(publicId, requireOwner(owner))
                        .orElseThrow(() -> new NotFoundException("图片不存在"));
        attachToDish(asset, dishId);
    }

    public void deleteStaged(long assetId) {
        var asset = repository.findById(assetId);
        if (asset.isEmpty()) return;
        if (asset.get().getDishId() != null) return;
        var storageKey = asset.get().getStorageKey();
        repository.deleteById(assetId);
        if (storageKey != null) deleteStorageAfterCommit(storageKey);
    }

    @Transactional
    public void deleteStaged(UUID publicId) {
        var asset = repository.findByPublicId(publicId);
        if (asset.isEmpty()) return;
        deleteStaged(asset.get());
    }

    @Transactional
    public void deleteStaged(UUID publicId, String owner) {
        var asset = repository.findByPublicIdAndOwner(publicId, requireOwner(owner));
        if (asset.isEmpty()) return;
        deleteStaged(asset.get());
    }

    private void deleteStaged(DishAssetEntity asset) {
        if (asset.getDishId() != null) return;
        var storageKey = asset.getStorageKey();
        repository.delete(asset);
        if (storageKey != null) deleteStorageAfterCommit(storageKey);
    }

    private void attachToDish(DishAssetEntity asset, long dishId) {
        if (asset.getDishId() != null && asset.getDishId() != dishId) {
            throw new IllegalStateException("图片已经关联到其他菜品");
        }
        repository
                .findByDishId(dishId)
                .filter(existing -> !Objects.equals(existing.getId(), asset.getId()))
                .ifPresent(
                        existing -> {
                            repository.delete(existing);
                            deleteStorageAfterCommit(existing.getStorageKey());
                        });
        asset.setDishId(dishId);
        repository.save(asset);
    }

    private void deleteStorageAfterCommit(String storageKey) {
        if (storageKey == null) return;
        try {
            storageService.delete(storageKey);
        } catch (Exception exception) {
            log.warn("Failed to clean dish asset storage {}: {}", storageKey, exception.toString());
        }
    }

    private static String normalizeMediaType(String mediaType) {
        var normalized = mediaType == null ? "" : mediaType.toLowerCase(Locale.ROOT).trim();
        return normalized.equals("image/jpg") ? "image/jpeg" : normalized;
    }

    private static String requireOwner(String owner) {
        var normalized = owner == null ? "" : owner.trim();
        if (normalized.isEmpty() || normalized.length() > 128) {
            throw new IllegalArgumentException("Resource owner is invalid");
        }
        return normalized;
    }

    private static String safeFilename(String filename) {
        var normalized =
                filename == null || filename.isBlank() ? "image" : filename.replace('\\', '/');
        normalized =
                normalized.substring(normalized.lastIndexOf('/') + 1).replaceAll("[\\r\\n\"]", "_");
        if (normalized.isBlank()) return "image";
        return normalized.substring(0, Math.min(normalized.length(), 255));
    }

    private static int[] readDimensions(byte[] data) {
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new InvalidNoteFileException("无法识别的图片内容");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                return new int[] {reader.getWidth(0), reader.getHeight(0)};
            } finally {
                reader.dispose();
            }
        } catch (InvalidNoteFileException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new InvalidNoteFileException("无法读取图片尺寸");
        }
    }

    static String sha256hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new RuntimeException("SHA-256 not available", exception);
        }
    }
}
