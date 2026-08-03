package com.yubai.blog.admin.ai;

import java.time.Instant;

public record ChatSessionResponse(
    Long id,
    String title,
    Instant createdAt,
    Instant updatedAt
) {
    static ChatSessionResponse from(ChatSessionEntity entity) {
        return new ChatSessionResponse(entity.getId(), entity.getTitle(), entity.getCreatedAt(), entity.getUpdatedAt());
    }
}
