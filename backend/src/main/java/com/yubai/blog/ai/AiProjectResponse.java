package com.yubai.blog.ai;

import java.time.Instant;

public record AiProjectResponse(
        Long id,
        String title,
        AiProjectStatus status,
        Instant archivedAt,
        int sortOrder,
        long version,
        int sessionCount,
        Instant createdAt,
        Instant updatedAt) {
    public static AiProjectResponse from(AiProjectEntity entity, int sessionCount) {
        return new AiProjectResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getStatus(),
                entity.getArchivedAt(),
                entity.getSortOrder(),
                entity.getVersion(),
                sessionCount,
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
