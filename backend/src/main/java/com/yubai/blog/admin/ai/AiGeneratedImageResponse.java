package com.yubai.blog.admin.ai;

import java.time.Instant;
import java.util.UUID;

public record AiGeneratedImageResponse(
    UUID publicId,
    String provider,
    String model,
    String prompt,
    String mediaType,
    long byteSize,
    Integer width,
    Integer height,
    String contentPath,
    Instant createdAt
) {
    static AiGeneratedImageResponse from(AiGeneratedImageEntity entity) {
        return new AiGeneratedImageResponse(
            entity.getPublicId(), entity.getProvider(), entity.getModel(), entity.getPrompt(),
            entity.getMediaType(), entity.getByteSize(), entity.getWidth(), entity.getHeight(),
            "/api/v1/admin/ai/images/" + entity.getPublicId() + "/content", entity.getCreatedAt());
    }
}
