package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.note.InvalidNoteFileException;
import com.yubai.blog.storage.StorageService;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class DishResourceOwnershipTest {
    private static final byte[] PNG =
            Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void stagedAssetQuotaIsCheckedUnderAnOwnerLock() {
        var repository = mock(DishAssetRepository.class);
        var storage = mock(StorageService.class);
        when(repository.countByOwnerAndDishIdIsNull("alice"))
                .thenReturn(DishAssetService.MAX_STAGED_COUNT_PER_OWNER);
        var service = new DishAssetService(repository, storage);
        var file = new MockMultipartFile("file", "cover.png", "image/png", PNG);

        assertThatThrownBy(() -> service.uploadStaged(file, "alice"))
                .isInstanceOf(InvalidNoteFileException.class)
                .hasMessageContaining("quota");

        verify(repository).lockOwnerQuota("alice");
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void anotherOwnerCannotAttachAStagedAsset() {
        var repository = mock(DishAssetRepository.class);
        var service = new DishAssetService(repository, mock(StorageService.class));
        var publicId = UUID.randomUUID();
        when(repository.findByPublicIdAndOwner(publicId, "bob")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assignToDish(publicId, 7, "bob"))
                .isInstanceOf(NotFoundException.class);

        verify(repository, never()).findByPublicId(publicId);
    }

    @Test
    void anotherOwnerCannotReadAStagedImport() {
        var stagingRepository = mock(DishImportStagingRepository.class);
        var staging =
                DishImportStagingEntity.create(
                        "alice",
                        "{}",
                        "imports/alice",
                        "image/png",
                        1,
                        Instant.now().plusSeconds(60));
        when(stagingRepository.findByToken(staging.getToken())).thenReturn(Optional.of(staging));
        var service =
                new DishImportService(
                        stagingRepository,
                        mock(DishRepository.class),
                        mock(DishCategoryService.class),
                        mock(DishAssetService.class),
                        mock(DishService.class),
                        mock(StorageService.class));

        assertThatThrownBy(() -> service.getStagedPreview(staging.getToken(), "bob"))
                .isInstanceOf(NotFoundException.class);
    }
}
