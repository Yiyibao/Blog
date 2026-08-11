package com.yubai.blog.admin.ai;

import com.yubai.blog.ai.AiProviderCapability;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiProviderModelRequest(
        @NotBlank @Size(max = 160) String model,
        List<AiProviderCapability> capabilities,
        List<@NotBlank @Size(max = 16) String> reasoningEfforts,
        Boolean enabled) {
    public boolean enabledOrDefault() {
        return enabled == null || enabled;
    }
}
