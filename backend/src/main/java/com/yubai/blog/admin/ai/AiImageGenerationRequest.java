package com.yubai.blog.admin.ai;

/** Provider-neutral image generation options. */
public record AiImageGenerationRequest(
    String prompt,
    int count,
    String size,
    String quality,
    String aspectRatio,
    String resolution
) {
}
