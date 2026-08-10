package com.yubai.blog.ai;

import java.time.Instant;

public record AiSessionResponse(
        Long id,
        String title,
        String mode,
        String summary,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public static AiSessionResponse from(AiSessionEntity entity) {
        return new AiSessionResponse(
                entity.getId(),
                entity.getTitle(),
                entity.getMode(),
                entity.getSummary(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
