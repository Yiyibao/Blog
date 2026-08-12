package com.yubai.blog.dish;

import java.time.Instant;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class DishImportStagingWriter {
    private final DishImportStagingRepository repository;

    public DishImportStagingWriter(DishImportStagingRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void assertQuota(String owner, long incomingBytes) {
        repository.lockOwnerQuota(owner);
        assertWithinQuota(owner, incomingBytes, Instant.now());
    }

    @Transactional
    public DishImportStagingEntity stage(
            String owner,
            String recipeJson,
            String storageKey,
            String mediaType,
            long byteSize,
            Instant expiresAt) {
        repository.lockOwnerQuota(owner);
        var now = Instant.now();
        assertWithinQuota(owner, byteSize, now);
        return repository.save(
                DishImportStagingEntity.create(
                        owner, recipeJson, storageKey, mediaType, byteSize, expiresAt));
    }

    private void assertWithinQuota(String owner, long incomingBytes, Instant now) {
        if (repository.countByOwnerAndConsumedFalseAndCancelledFalseAndExpiresAtAfter(owner, now)
                        >= DishImportService.MAX_ACTIVE_IMPORT_COUNT_PER_OWNER
                || repository.sumActiveBytes(owner, now) + incomingBytes
                        > DishImportService.MAX_ACTIVE_IMPORT_BYTES_PER_OWNER) {
            throw new InvalidRecipeException("Staged recipe import quota exceeded");
        }
    }
}
