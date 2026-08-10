package com.yubai.blog.ai;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AiTaskPartRequest(
        @NotNull AiPartKind kind,
        @Size(max = 32_000) String text,
        UUID fileId,
        UUID artifactId,
        @Size(max = 500) String sourceRef) {}
