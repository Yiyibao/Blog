package com.yubai.blog.ai;

import java.time.Instant;
import java.util.UUID;

public record AiArtifactResponse(
        UUID id,
        UUID taskId,
        String name,
        String mediaType,
        long sizeBytes,
        String sha256,
        AiArtifactStatus status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt) {
    public static AiArtifactResponse from(AiArtifactEntity entity) {
        return new AiArtifactResponse(
                entity.getId(),
                entity.getTaskId(),
                entity.getName(),
                entity.getMediaType(),
                entity.getSizeBytes(),
                entity.getSha256(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
