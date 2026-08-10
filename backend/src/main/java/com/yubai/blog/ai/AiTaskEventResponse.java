package com.yubai.blog.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;

public record AiTaskEventResponse(
        UUID taskId, long sequence, String eventType, JsonNode payload, Instant createdAt) {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static AiTaskEventResponse from(AiTaskEventEntity entity) {
        return new AiTaskEventResponse(
                entity.getTaskId(),
                entity.getSequence(),
                entity.getEventType(),
                parse(entity.getSanitizedPayload()),
                entity.getCreatedAt());
    }

    private static JsonNode parse(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception ignored) {
            return MAPPER.createObjectNode();
        }
    }
}
