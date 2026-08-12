package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.storage.StorageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DishResourceMaintenanceServiceTest {
    @Test
    void expiredMetadataAndStorageAreCleanedAndCounted() {
        var stagingRepository = mock(DishImportStagingRepository.class);
        var assetRepository = mock(DishAssetRepository.class);
        var storage = mock(StorageService.class);
        var meters = new SimpleMeterRegistry();
        var staging =
                DishImportStagingEntity.create(
                        "alice",
                        "{}",
                        "imports/expired",
                        "image/png",
                        3,
                        Instant.now().minusSeconds(1));
        var asset =
                DishAssetEntity.create(
                        "alice", "dish-assets/expired", "cover.png", "image/png", 3, "abc", 1, 1);
        when(stagingRepository.findByExpiresAtBefore(any())).thenReturn(List.of(staging));
        when(assetRepository.findByDishIdIsNullAndExpiresAtBefore(any()))
                .thenReturn(List.of(asset));
        var service =
                new DishResourceMaintenanceService(
                        stagingRepository, assetRepository, storage, meters);

        var result = service.cleanupExpired();

        assertThat(result.expiredImports()).isEqualTo(1);
        assertThat(result.expiredAssets()).isEqualTo(1);
        assertThat(result.storageDeletes()).isEqualTo(2);
        verify(storage).delete("imports/expired");
        verify(storage).delete("dish-assets/expired");
        assertThat(meters.counter("blog.resources.cleanup", "type", "dish_import").count())
                .isEqualTo(1);
    }
}
