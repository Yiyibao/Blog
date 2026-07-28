package com.yubai.blog.admin.ai;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 管理端供应商视图：密钥永不回显，只暴露 hasKey 与尾 4 位。
 */
public record AiProviderResponse(
    Long id,
    String name,
    String baseUrl,
    List<String> models,
    String defaultModel,
    boolean enabled,
    boolean isDefault,
    boolean hasKey,
    String keyTail,
    int dailyRequestLimit,
    int dailyTokenLimit,
    AiProviderType providerType,
    Instant createdAt,
    Instant updatedAt
) {
    public static AiProviderResponse from(AiProviderEntity entity, String keyTail) {
        return new AiProviderResponse(
            entity.getId(),
            entity.getName(),
            entity.getBaseUrl(),
            parseModels(entity.getModels()),
            entity.getDefaultModel(),
            entity.isEnabled(),
            entity.isDefault(),
            entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank(),
            keyTail,
            entity.getDailyRequestLimit(),
            entity.getDailyTokenLimit(),
            entity.getProviderType(),
            entity.getCreatedAt(),
            entity.getUpdatedAt()
        );
    }

    static List<String> parseModels(String joined) {
        if (joined == null || joined.isBlank()) {
            return List.of();
        }
        return Arrays.stream(joined.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .toList();
    }
}
