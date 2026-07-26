package com.yubai.blog.admin.ai;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ChatResponse(
    String content,
    String model,
    Usage usage
) {
    public record Usage(
        @JsonProperty("promptTokens") int promptTokens,
        @JsonProperty("completionTokens") int completionTokens,
        @JsonProperty("totalTokens") int totalTokens
    ) {}
}
