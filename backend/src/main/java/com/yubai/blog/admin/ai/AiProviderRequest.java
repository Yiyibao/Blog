package com.yubai.blog.admin.ai;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiProviderRequest(
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Size(max = 500) String baseUrl,
        /** 创建时可空（本地无鉴权端点）；更新时留空表示保留原密钥。 */
        @Size(max = 500) String apiKey,
        List<@NotBlank @Size(max = 120) String> models,
        @NotBlank @Size(max = 120) String defaultModel,
        Boolean enabled,
        @Min(1) @Max(100_000) Integer dailyRequestLimit,
        @Min(1000) @Max(100_000_000) Integer dailyTokenLimit,
        AiProviderType providerType,
        List<AiProviderModelRequest> modelCapabilities) {
    public AiProviderRequest(
            String name,
            String baseUrl,
            String apiKey,
            List<String> models,
            String defaultModel,
            Boolean enabled,
            Integer dailyRequestLimit,
            Integer dailyTokenLimit,
            AiProviderType providerType) {
        this(
                name,
                baseUrl,
                apiKey,
                models,
                defaultModel,
                enabled,
                dailyRequestLimit,
                dailyTokenLimit,
                providerType,
                null);
    }

    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }

    public int dailyRequestLimitOrDefault() {
        return dailyRequestLimit == null ? 200 : dailyRequestLimit;
    }

    public int dailyTokenLimitOrDefault() {
        return dailyTokenLimit == null ? 200_000 : dailyTokenLimit;
    }

    public AiProviderType providerTypeOrDefault() {
        return providerType != null ? providerType : AiProviderType.OPENAI_COMPATIBLE;
    }
}
