package com.yubai.blog.dish;

import java.time.Instant;
import java.util.UUID;

public record DishAssetResponse(
    UUID publicId,
    String fileName,
    String mediaType,
    long byteSize,
    Integer width,
    Integer height,
    String url,
    Instant createdAt
) {
    static DishAssetResponse from(DishAssetEntity entity) {
        return new DishAssetResponse(
            entity.getPublicId(),
            entity.getFileName(),
            entity.getMediaType(),
            entity.getByteSize(),
            entity.getWidth(),
            entity.getHeight(),
            "/api/v1/dish-assets/" + entity.getPublicId(),
            entity.getCreatedAt()
        );
    }
}
