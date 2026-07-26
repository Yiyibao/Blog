package com.yubai.blog.admin.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record ChatMessage(
    @NotBlank
    @Pattern(regexp = "^(user|assistant)$", message = "must be 'user' or 'assistant'")
    String role,

    @NotBlank
    String content
) {}
