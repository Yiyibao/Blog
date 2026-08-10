package com.yubai.blog.ai;

public enum AiArtifactFormat {
    MARKDOWN("text/markdown", ".md"),
    TEXT("text/plain", ".txt"),
    JSON("application/json", ".json"),
    CSV("text/csv", ".csv"),
    IMAGE(null, null);

    private final String mediaType;
    private final String extension;

    AiArtifactFormat(String mediaType, String extension) {
        this.mediaType = mediaType;
        this.extension = extension;
    }

    public String mediaType() {
        return mediaType;
    }

    public String extension() {
        return extension;
    }
}
