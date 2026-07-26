package com.yubai.blog.admin.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ChatRequest(
    @Valid
    @NotNull
    @Size(min = 1)
    List<ChatMessage> messages
) {}
