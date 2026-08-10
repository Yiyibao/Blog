package com.yubai.blog.ai;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AiMemoryCreateRequest(
        @NotBlank @Size(max = 80) String scope,
        @NotBlank @Size(max = 40) String kind,
        @NotBlank @Size(max = 4_000) String content,
        UUID sourceTaskId,
        @Size(max = 500) String sourceRef,
        @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal confidence,
        Instant expiresAt) {}
