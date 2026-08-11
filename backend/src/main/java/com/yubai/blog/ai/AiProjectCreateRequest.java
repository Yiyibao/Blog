package com.yubai.blog.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiProjectCreateRequest(@NotBlank @Size(max = 160) String title) {}
