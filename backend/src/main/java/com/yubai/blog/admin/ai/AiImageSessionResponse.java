package com.yubai.blog.admin.ai;

import java.time.Instant;

public record AiImageSessionResponse(
    Long id,
    String title,
    Instant createdAt,
    Instant updatedAt
) {
    static AiImageSessionResponse from(AiImageSessionEntity entity) {
        return new AiImageSessionResponse(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
