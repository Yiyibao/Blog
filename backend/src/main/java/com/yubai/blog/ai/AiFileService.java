package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.AiPlatformProperties;
import com.yubai.blog.storage.StorageService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AiFileService {
    private final AiFileRepository repository;
    private final AiFileParserRegistry parsers;
    private final StorageService storage;
    private final AiPlatformProperties properties;

    public AiFileService(
            AiFileRepository repository,
            AiFileParserRegistry parsers,
            StorageService storage,
            AiPlatformProperties properties) {
        this.repository = repository;
        this.parsers = parsers;
        this.storage = storage;
        this.properties = properties;
    }

    @Transactional
    public AiFileResponse upload(String owner, MultipartFile multipart, AiFileRetention retention) {
        if (multipart == null || multipart.isEmpty()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "AI file is empty");
        }
        var maxBytes = Math.max(1, properties.getMaxFileBytes());
        if (multipart.getSize() > maxBytes) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "AI file size limit exceeded");
        }
        repository.lockOwnerQuota(owner);
        var excluded = List.of(AiFileStatus.DELETED, AiFileStatus.EXPIRED);
        if (repository.countByOwnerAndStatusNotIn(owner, excluded)
                >= Math.max(1, properties.getMaxOwnerFiles())) {
            throw new AiServiceException(
                    HttpStatus.PAYLOAD_TOO_LARGE, "AI file count quota exceeded");
        }
        var retained = repository.sumRetainedBytes(owner, excluded);
        if (retained + multipart.getSize() > Math.max(1, properties.getMaxOwnerFileBytes())) {
            throw new AiServiceException(HttpStatus.PAYLOAD_TOO_LARGE, "AI file quota exceeded");
        }
        var bytes = readBounded(multipart, maxBytes);
        var name = AiFileParserRegistry.safeFilename(multipart.getOriginalFilename());
        var parsed = parsers.parse(name, multipart.getContentType(), bytes);
        var id = UUID.randomUUID();
        var storageKey = "ai-files/" + id + extension(name);
        var effectiveRetention = retention == null ? AiFileRetention.THIRTY_DAYS : retention;
        var expiresAt =
                effectiveRetention == AiFileRetention.PINNED
                        ? null
                        : Instant.now()
                                .plus(
                                        Math.max(1, properties.getFileRetentionDays()),
                                        ChronoUnit.DAYS);
        storage.store(storageKey, bytes);
        try {
            var entity =
                    AiFileEntity.ready(
                            id,
                            owner,
                            storageKey,
                            name,
                            parsed.mediaType(),
                            bytes.length,
                            sha256(bytes),
                            effectiveRetention,
                            expiresAt,
                            parsed.extractedText());
            return AiFileResponse.from(repository.saveAndFlush(entity));
        } catch (RuntimeException exception) {
            storage.delete(storageKey);
            throw exception;
        }
    }

    @Transactional(readOnly = true)
    public List<AiFileResponse> list(String owner) {
        return repository
                .findByOwnerAndStatusNotOrderByCreatedAtDesc(owner, AiFileStatus.DELETED)
                .stream()
                .map(AiFileResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AiFileResponse get(UUID id, String owner) {
        return AiFileResponse.from(requireOwned(id, owner));
    }

    @Transactional(readOnly = true)
    public AiFileContent readReady(UUID id, String owner) {
        var entity = requireOwned(id, owner);
        if (entity.getStatus() != AiFileStatus.READY) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI file is not ready");
        }
        var bytes = storage.read(entity.getStorageKey());
        if (bytes.length != entity.getSizeBytes() || !sha256(bytes).equals(entity.getSha256())) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI file integrity check failed");
        }
        return new AiFileContent(entity, bytes);
    }

    @Transactional
    public void delete(UUID id, String owner) {
        var entity = requireOwned(id, owner);
        if (entity.getStatus() == AiFileStatus.DELETED) return;
        storage.delete(entity.getStorageKey());
        entity.forget();
        repository.save(entity);
    }

    @Transactional(readOnly = true)
    public AiFileEntity requireOwned(UUID id, String owner) {
        return repository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI file does not exist"));
    }

    private static byte[] readBounded(MultipartFile multipart, int maxBytes) {
        try (InputStream input = multipart.getInputStream();
                var output = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024))) {
            var buffer = new byte[8192];
            var total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new AiServiceException(
                            HttpStatus.BAD_REQUEST, "AI file size limit exceeded");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        } catch (AiServiceException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "Unable to read AI file");
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String extension(String filename) {
        var index = filename.lastIndexOf('.');
        if (index < 0) return "";
        var extension = filename.substring(index).toLowerCase(java.util.Locale.ROOT);
        return extension.matches("\\.[a-z0-9]{1,10}") ? extension : "";
    }

    public record AiFileContent(AiFileEntity metadata, byte[] bytes) {
        public AiFileContent {
            bytes = bytes.clone();
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
}
