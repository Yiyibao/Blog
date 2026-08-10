package com.yubai.blog.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record AiArtifactCreateRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull AiArtifactFormat format,
        @Size(max = 2_000_000) String content,
        UUID sourceImageId) {}
