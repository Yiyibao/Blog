package com.yubai.blog.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskEventService {
    private final AiTaskEventRepository repository;
    private final AiTaskRepository taskRepository;
    private final ObjectMapper objectMapper;

    public AiTaskEventService(
            AiTaskEventRepository repository,
            AiTaskRepository taskRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiTaskEventEntity append(UUID taskId, String eventType, Map<String, ?> payload) {
        taskRepository
                .lockById(taskId)
                .orElseThrow(() -> new IllegalStateException("AI task does not exist"));
        var sequence =
                repository
                                .findFirstByTaskIdOrderBySequenceDesc(taskId)
                                .map(AiTaskEventEntity::getSequence)
                                .orElse(0L)
                        + 1;
        return repository.save(
                AiTaskEventEntity.create(taskId, sequence, eventType, serialize(payload)));
    }

    @Transactional(readOnly = true)
    public List<AiTaskEventResponse> replay(UUID taskId, long afterSequence) {
        return repository
                .findByTaskIdAndSequenceGreaterThanOrderBySequenceAsc(
                        taskId, Math.max(0, afterSequence))
                .stream()
                .map(AiTaskEventResponse::from)
                .toList();
    }

    private String serialize(Map<String, ?> payload) {
        try {
            return objectMapper.writeValueAsString(payload == null ? Map.of() : payload);
        } catch (JsonProcessingException exception) {
            return "{}";
        }
    }
}
