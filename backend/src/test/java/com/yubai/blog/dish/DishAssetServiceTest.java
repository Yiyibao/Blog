package com.yubai.blog.dish;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.storage.StorageService;

@ExtendWith(MockitoExtension.class)
class DishAssetServiceTest {

    @Mock
    DishAssetRepository repository;

    @Mock
    StorageService storageService;

    @InjectMocks
    DishAssetService service;

    @Test
    void findByPublicIdReturnsAsset() {
        var asset = DishAssetEntity.create("key", "cover.jpg", "image/jpeg", 100, "abc", 800, 600);
        when(repository.findByPublicId(asset.getPublicId())).thenReturn(Optional.of(asset));
        var result = service.findByPublicId(asset.getPublicId());
        assertThat(result.getPublicId()).isEqualTo(asset.getPublicId());
    }

    @Test
    void findByPublicIdThrowsWhenNotFound() {
        var id = UUID.randomUUID();
        when(repository.findByPublicId(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.findByPublicId(id)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void createForDishStoresAndSaves() {
        var data = "test".getBytes(StandardCharsets.UTF_8);
        when(repository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> invocation.getArgument(0));
        var asset = service.createForDish(1L, "dish-assets/uuid/image.jpg", "cover.jpg",
            "image/jpeg", data, 800, 600);
        assertThat(asset.getDishId()).isEqualTo(1L);
        assertThat(asset.getSha256()).isNotEmpty();
    }

    @Test
    void assignToDishSetsDishId() {
        var asset = DishAssetEntity.create("key", "cover.jpg", "image/jpeg", 100, "abc", null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(asset));
        when(repository.save(asset)).thenReturn(asset);
        service.assignToDish(1L, 42L);
        assertThat(asset.getDishId()).isEqualTo(42L);
        verify(repository).save(asset);
    }

    @Test
    void deleteStagedRemovesFromStorage() {
        var asset = DishAssetEntity.create("staged-key", "cover.jpg", "image/jpeg", 100, "abc", null, null);
        when(repository.findById(1L)).thenReturn(Optional.of(asset));
        service.deleteStaged(1L);
        verify(storageService).delete("staged-key");
        verify(repository).deleteById(1L);
    }

    @Test
    void sha256hexReturnsExpectedHash() {
        var data = "hello".getBytes(StandardCharsets.UTF_8);
        var hash = DishAssetService.sha256hex(data);
        assertThat(hash).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }
}
