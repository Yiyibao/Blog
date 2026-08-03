package com.yubai.blog.admin.ai;

import java.util.List;

public record AiImageGenerateResponse(
    Long sessionId,
    String sessionTitle,
    List<AiGeneratedImageResponse> images
) {
}
