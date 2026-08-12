package com.yubai.blog.admin.recipe;

import com.yubai.blog.dish.YrecipePackage;
import java.time.Instant;
import java.util.UUID;

public record RecipeExtractionResponse(
        Long id,
        UUID idempotencyKey,
        String sourceType,
        String status,
        String stage,
        int progress,
        int attempts,
        Long providerId,
        String model,
        UUID resultImportToken,
        String errorCode,
        String safeErrorMessage,
        ImportPreview preview,
        Instant createdAt,
        Instant startedAt,
        Instant heartbeatAt,
        Instant finishedAt) {
    public record ImportPreview(
            UUID token,
            Instant expiresAt,
            YrecipePackage recipe,
            java.util.List<String> warnings,
            String categoryMatch,
            boolean slugAvailable,
            String coverPreviewUrl) {}

    public static RecipeExtractionResponse from(
            RecipeExtractionJobEntity entity, ImportPreview preview) {
        return new RecipeExtractionResponse(
                entity.getId(),
                entity.getIdempotencyKey(),
                entity.getSourceType(),
                entity.getStatus(),
                entity.getStage(),
                entity.getProgress(),
                entity.getAttempts(),
                entity.getProviderId(),
                entity.getModel(),
                entity.getResultImportToken(),
                entity.getErrorCode(),
                entity.getSafeErrorMessage(),
                preview,
                entity.getCreatedAt(),
                entity.getStartedAt(),
                entity.getHeartbeatAt(),
                entity.getFinishedAt());
    }
}
