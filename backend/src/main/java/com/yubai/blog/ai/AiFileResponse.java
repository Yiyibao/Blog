package com.yubai.blog.ai;

import java.time.Instant;
import java.util.UUID;

public record AiFileResponse(
        UUID id,
        String name,
        String mediaType,
        long sizeBytes,
        String sha256,
        AiFileStatus status,
        AiFileRetention retention,
        Instant expiresAt,
        int referenceCount,
        Instant createdAt,
        Instant updatedAt) {
    public static AiFileResponse from(AiFileEntity entity) {
        return new AiFileResponse(
                entity.getId(),
                entity.getOriginalName(),
                entity.getMediaType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getStatus(),
                entity.getRetention(),
                entity.getExpiresAt(),
                entity.getReferenceCount(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
