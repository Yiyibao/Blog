package com.yubai.blog.dish;

import com.yubai.blog.storage.StorageService;
import io.micrometer.core.instrument.MeterRegistry;
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
public class DishResourceMaintenanceService {
    private static final Logger LOGGER =
            LoggerFactory.getLogger(DishResourceMaintenanceService.class);
    private final DishImportStagingRepository stagingRepository;
    private final DishAssetRepository assetRepository;
    private final StorageService storage;
    private final MeterRegistry meters;

    public DishResourceMaintenanceService(
            DishImportStagingRepository stagingRepository,
            DishAssetRepository assetRepository,
            StorageService storage,
            MeterRegistry meters) {
        this.stagingRepository = stagingRepository;
        this.assetRepository = assetRepository;
        this.storage = storage;
        this.meters = meters;
    }

    @Scheduled(fixedDelayString = "${app.dish.resource-cleanup-interval-ms:3600000}")
    @Transactional
    public CleanupResult cleanupExpired() {
        var now = Instant.now();
        var imports = stagingRepository.findByExpiresAtBefore(now);
        var assets = assetRepository.findByDishIdIsNullAndExpiresAtBefore(now);
        var storageKeys = new ArrayList<String>();
        imports.stream()
                .map(DishImportStagingEntity::getStorageKey)
                .filter(key -> key != null && !key.isBlank())
                .forEach(storageKeys::add);
        assets.stream()
                .map(DishAssetEntity::getStorageKey)
                .filter(key -> key != null && !key.isBlank())
                .forEach(storageKeys::add);
        stagingRepository.deleteAll(imports);
        assetRepository.deleteAll(assets);
        deleteAfterCommit(storageKeys);
        meters.counter("blog.resources.cleanup", "type", "dish_import").increment(imports.size());
        meters.counter("blog.resources.cleanup", "type", "dish_asset").increment(assets.size());
        return new CleanupResult(imports.size(), assets.size(), storageKeys.size());
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
            meters.counter("blog.resources.cleanup.failures", "type", "dish").increment();
            LOGGER.warn("Dish resource cleanup will retry after storage deletion failed");
        }
    }

    public record CleanupResult(int expiredImports, int expiredAssets, int storageDeletes) {}
}
