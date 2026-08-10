package com.yubai.blog.ai;

import java.time.Instant;
import java.util.UUID;

public record AiTaskPartResponse(
        int sequence,
        AiPartRole role,
        AiPartKind kind,
        String text,
        UUID fileId,
        UUID artifactId,
        String sourceRef,
        Instant createdAt) {
    public static AiTaskPartResponse from(AiTaskPartEntity entity) {
        return new AiTaskPartResponse(
                entity.getSequence(),
                entity.getRole(),
                entity.getKind(),
                entity.getTextContent(),
                entity.getFileId(),
                entity.getArtifactId(),
                entity.getSourceRef(),
                entity.getCreatedAt());
    }
}
