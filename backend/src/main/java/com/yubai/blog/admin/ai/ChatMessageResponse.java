package com.yubai.blog.admin.ai;

import java.time.Instant;

public record ChatMessageResponse(
    Long id,
    String role,
    String content,
    Instant createdAt
) {
    static ChatMessageResponse from(ChatMessageEntity entity) {
        return new ChatMessageResponse(entity.getId(), entity.getRole(), entity.getContent(), entity.getCreatedAt());
    }
}
