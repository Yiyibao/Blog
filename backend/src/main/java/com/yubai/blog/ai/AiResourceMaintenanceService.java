package com.yubai.blog.ai;

import com.yubai.blog.config.AiPlatformProperties;
import com.yubai.blog.storage.StorageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class AiResourceMaintenanceService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(AiResourceMaintenanceService.class);
    private final AiFileRepository fileRepository;
    private final AiArtifactRepository artifactRepository;
    private final StorageService storage;
    private final AiPlatformProperties properties;

    public AiResourceMaintenanceService(
            AiFileRepository fileRepository,
            AiArtifactRepository artifactRepository,
            StorageService storage,
            AiPlatformProperties properties) {
        this.fileRepository = fileRepository;
        this.artifactRepository = artifactRepository;
        this.storage = storage;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.ai.platform.cleanup-interval-ms:3600000}")
    @Transactional
    public CleanupResult cleanupExpired() {
        var now = Instant.now();
        var storageKeys = new ArrayList<String>();
        var files = List.<AiFileEntity>of();
        var artifacts = List.<AiArtifactEntity>of();
        if (properties.isMultimodalEnabled()) {
            files =
                    fileRepository.findByStatusInAndExpiresAtBefore(
                            List.of(AiFileStatus.READY, AiFileStatus.EXPIRED), now);
            files.forEach(
                    file -> {
                        file.expire();
                        storageKeys.add(file.getStorageKey());
                    });
            fileRepository.saveAll(files);
        }
        if (properties.isArtifactsEnabled()) {
            artifacts =
                    artifactRepository.findByStatusInAndExpiresAtBefore(
                            List.of(AiArtifactStatus.READY, AiArtifactStatus.EXPIRED), now);
            artifacts.forEach(
                    artifact -> {
                        artifact.expire();
                        storageKeys.add(artifact.getStorageKey());
                    });
            artifactRepository.saveAll(artifacts);
        }
        deleteAfterCommit(storageKeys);
        return new CleanupResult(files.size(), artifacts.size());
    }

    private void deleteAfterCommit(List<String> storageKeys) {
        if (storageKeys.isEmpty()) return;
        var action = (Runnable) () -> storageKeys.forEach(this::deleteQuietly);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        action.run();
                    }
                });
    }

    private void deleteQuietly(String storageKey) {
        try {
            storage.delete(storageKey);
        } catch (RuntimeException exception) {
            LOGGER.warn("AI expired resource cleanup will retry after storage deletion failed");
        }
    }

    public record CleanupResult(int expiredFiles, int expiredArtifacts) {}
}
