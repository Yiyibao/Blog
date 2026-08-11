package com.yubai.blog.ai;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiSessionCreateRequest(
        @Size(max = 160) String title,
        @Pattern(regexp = "WORKSPACE|COMPACT|PET") String mode,
        Long projectId) {}
