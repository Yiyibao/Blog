package com.yubai.blog.admin.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequest(
    @Valid
    @NotNull
    @Size(min = 1)
    List<ChatMessage> messages,
    /** 可选：指定供应商；空则用默认供应商（或 env 回退）。 */
    Long providerId,
    /** 可选：指定模型；空则用供应商默认模型。 */
    @Size(max = 120) String model
) {
    public ChatRequest(List<ChatMessage> messages) {
        this(messages, null, null);
    }
}
