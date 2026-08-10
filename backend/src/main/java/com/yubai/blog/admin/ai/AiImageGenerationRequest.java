package com.yubai.blog.admin.ai;

/** Provider-neutral image generation options. */
public record AiImageGenerationRequest(
        String prompt,
        int count,
        String size,
        String quality,
        String aspectRatio,
        String resolution,
        ReferenceImage referenceImage) {
    public AiImageGenerationRequest(
            String prompt,
            int count,
            String size,
            String quality,
            String aspectRatio,
            String resolution) {
        this(prompt, count, size, quality, aspectRatio, resolution, null);
    }

    /** A validated, in-memory source image used for one image-to-image request. */
    public record ReferenceImage(byte[] bytes, String mediaType) {}
}
