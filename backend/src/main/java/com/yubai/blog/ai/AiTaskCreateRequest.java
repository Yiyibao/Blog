package com.yubai.blog.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiTaskCreateRequest(
        Long sessionId,
        @Size(max = 160) String sessionTitle,
        @Pattern(regexp = "CHAT|ANALYZE|GENERATE") String taskType,
        Long providerId,
        @Size(max = 160) String model,
        @Size(max = 160) String idempotencyKey,
        @Valid @NotEmpty @Size(max = 12) List<AiTaskPartRequest> parts) {}
