package com.yubai.blog.ai;

import jakarta.validation.constraints.Size;

public record AiSessionUpdateRequest(@Size(max = 160) String title, Long projectId, long version) {}
