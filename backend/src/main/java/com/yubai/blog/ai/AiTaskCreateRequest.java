package com.yubai.blog.ai;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AiTaskCreateRequest(
        Long sessionId,
        Long projectId,
        @Size(max = 160) String sessionTitle,
        @Pattern(regexp = "CHAT|ANALYZE|GENERATE") String taskType,
        Long providerId,
        @Size(max = 160) String model,
        @Pattern(regexp = "none|low|medium|high|xhigh|max") String reasoningEffort,
        @Size(max = 160) String idempotencyKey,
        @Valid @NotEmpty @Size(max = 12) List<AiTaskPartRequest> parts) {
    public AiTaskCreateRequest(
            Long sessionId,
            String sessionTitle,
            String taskType,
            Long providerId,
            String model,
            String idempotencyKey,
            List<AiTaskPartRequest> parts) {
        this(
                sessionId,
                null,
                sessionTitle,
                taskType,
                providerId,
                model,
                null,
                idempotencyKey,
                parts);
    }
}
