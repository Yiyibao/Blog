package com.yubai.blog.ai;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AiTaskResponse(
        UUID id,
        Long sessionId,
        String taskType,
        AiTaskStatus status,
        Long providerId,
        String providerType,
        String model,
        Long requestedProviderId,
        String requestedModel,
        String requestedReasoningEffort,
        Long resolvedProviderId,
        String resolvedModel,
        String resolvedReasoningEffort,
        String requiredCapabilities,
        String routeReason,
        String errorCode,
        String errorMessage,
        long version,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt,
        List<AiTaskPartResponse> parts) {
    public static AiTaskResponse from(AiTaskEntity entity, List<AiTaskPartEntity> parts) {
        return new AiTaskResponse(
                entity.getId(),
                entity.getSessionId(),
                entity.getTaskType(),
                entity.getStatus(),
                entity.getProviderId(),
                entity.getProviderType(),
                entity.getModel(),
                entity.getRequestedProviderId(),
                entity.getRequestedModel(),
                entity.getRequestedReasoningEffort(),
                entity.getResolvedProviderId(),
                entity.getResolvedModel(),
                entity.getResolvedReasoningEffort(),
                entity.getRequiredCapabilities(),
                entity.getRouteReason(),
                entity.getErrorCode(),
                entity.getErrorMessage(),
                entity.getVersion(),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                parts.stream().map(AiTaskPartResponse::from).toList());
    }
}
