package com.yubai.blog.ai;

import java.util.List;

public record AiModelResult(
        String text, String providerType, String model, List<AiToolCall> toolCalls) {
    public AiModelResult {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public AiModelResult(String text, String providerType, String model) {
        this(text, providerType, model, List.of());
    }
}
