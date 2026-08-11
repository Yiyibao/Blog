package com.yubai.blog.admin.ai;

import com.yubai.blog.ai.AiProviderCapability;
import java.time.Instant;
import java.util.List;

public record AiProviderModelResponse(
        String model,
        List<AiProviderCapability> capabilities,
        List<String> reasoningEfforts,
        boolean enabled,
        long version,
        Instant updatedAt) {
    public static AiProviderModelResponse from(AiProviderModelEntity entity) {
        return new AiProviderModelResponse(
                entity.getModel(),
                entity.capabilities().stream().sorted().toList(),
                entity.reasoningEfforts().stream().sorted().toList(),
                entity.isEnabled(),
                entity.getVersion(),
                entity.getUpdatedAt());
    }
}
