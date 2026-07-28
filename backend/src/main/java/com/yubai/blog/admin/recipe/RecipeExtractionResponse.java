package com.yubai.blog.admin.recipe;

import java.time.Instant;
import java.util.UUID;

import com.yubai.blog.dish.YrecipePackage;

public record RecipeExtractionResponse(
    Long id,
    String sourceType,
    String status,
    String stage,
    int progress,
    Long providerId,
    String model,
    UUID resultImportToken,
    String safeErrorMessage,
    ImportPreview preview,
    Instant createdAt,
    Instant startedAt,
    Instant finishedAt
) {
    public record ImportPreview(
        UUID token,
        Instant expiresAt,
        YrecipePackage recipe,
        java.util.List<String> warnings,
        String categoryMatch,
        boolean slugAvailable,
        String coverPreviewUrl
    ) {}

    public static RecipeExtractionResponse from(RecipeExtractionJobEntity entity, ImportPreview preview) {
        return new RecipeExtractionResponse(
            entity.getId(), entity.getSourceType(), entity.getStatus(),
            entity.getStage(), entity.getProgress(),
            entity.getProviderId(), entity.getModel(),
            entity.getResultImportToken(), entity.getSafeErrorMessage(),
            preview,
            entity.getCreatedAt(), entity.getStartedAt(), entity.getFinishedAt()
        );
    }
}
