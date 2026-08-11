package com.yubai.blog.ai;

import java.time.Instant;
import java.util.UUID;

public record AiConversationMessageResponse(
        UUID taskId,
        int sequence,
        AiPartRole role,
        AiPartKind kind,
        String text,
        UUID fileId,
        UUID artifactId,
        String sourceRef,
        Instant createdAt) {
    public static AiConversationMessageResponse from(AiTaskPartEntity part) {
        return new AiConversationMessageResponse(
                part.getTaskId(),
                part.getSequence(),
                part.getRole(),
                part.getKind(),
                part.getTextContent(),
                part.getFileId(),
                part.getArtifactId(),
                part.getSourceRef(),
                part.getCreatedAt());
    }
}
