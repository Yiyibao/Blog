package com.yubai.blog.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AiMemoryResponse(
        UUID id,
        String scope,
        String kind,
        String content,
        UUID sourceTaskId,
        String sourceRef,
        AiMemoryStatus status,
        BigDecimal confidence,
        Instant expiresAt,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public static AiMemoryResponse from(AiMemoryEntity entity) {
        return new AiMemoryResponse(
                entity.getId(),
                entity.getScope(),
                entity.getKind(),
                entity.getContent(),
                entity.getSourceTaskId(),
                entity.getSourceRef(),
                entity.getStatus(),
                entity.getConfidence(),
                entity.getExpiresAt(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt());
    }
}
