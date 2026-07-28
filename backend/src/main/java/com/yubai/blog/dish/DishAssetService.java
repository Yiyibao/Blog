package com.yubai.blog.dish;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.storage.StorageService;

@Service
public class DishAssetService {
    private static final Logger log = LoggerFactory.getLogger(DishAssetService.class);
    private final DishAssetRepository repository;
    private final StorageService storageService;

    public DishAssetService(DishAssetRepository repository, StorageService storageService) {
        this.repository = repository;
        this.storageService = storageService;
    }

    public DishAssetEntity findByPublicId(UUID publicId) {
        return repository.findByPublicId(publicId)
            .orElseThrow(() -> new NotFoundException("图片不存在"));
    }

    public DishAssetEntity findByDishId(long dishId) {
        return repository.findByDishId(dishId)
            .orElseThrow(() -> new NotFoundException("图片不存在"));
    }

    public byte[] readContent(UUID publicId) {
        var asset = findByPublicId(publicId);
        return storageService.read(asset.getStorageKey());
    }

    public DishAssetEntity createForDish(long dishId, String storageKey, String fileName,
                                         String mediaType, byte[] data, Integer width, Integer height) {
        storageService.store(storageKey, data);
        var sha256 = sha256hex(data);
        var asset = DishAssetEntity.create(storageKey, fileName, mediaType, data.length, sha256, width, height);
        asset.setDishId(dishId);
        return repository.save(asset);
    }

    public DishAssetEntity createStaged(String storageKey, String fileName,
                                        String mediaType, byte[] data, Integer width, Integer height) {
        var sha256 = sha256hex(data);
        var asset = DishAssetEntity.create(storageKey, fileName, mediaType, data.length, sha256, width, height);
        return repository.save(asset);
    }

    public void assignToDish(long assetId, long dishId) {
        var asset = repository.findById(assetId)
            .orElseThrow(() -> new NotFoundException("图片不存在"));
        asset.setDishId(dishId);
        repository.save(asset);
    }

    public void deleteStaged(long assetId) {
        var asset = repository.findById(assetId);
        if (asset.isEmpty()) return;
        var storageKey = asset.get().getStorageKey();
        repository.deleteById(assetId);
        if (storageKey != null) {
            try {
                storageService.delete(storageKey);
            } catch (Exception e) {
                log.warn("Failed to clean staged asset {}: {}", storageKey, e.toString());
            }
        }
    }

    static String sha256hex(byte[] data) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
