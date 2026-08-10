package com.yubai.blog.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public record AiMemoryUpdateRequest(
        @NotBlank @Size(max = 80) String scope,
        @NotBlank @Size(max = 40) String kind,
        @NotBlank @Size(max = 4_000) String content,
        Instant expiresAt,
        @NotNull Long version) {}
