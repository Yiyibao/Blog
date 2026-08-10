package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiTaskService {
    private final AiTaskRepository taskRepository;
    private final AiTaskPartRepository partRepository;
    private final AiFileRepository fileRepository;
    private final AiArtifactRepository artifactRepository;
    private final AiSessionService sessionService;
    private final AiTaskEventService eventService;
    private final AiTaskCancellationRegistry cancellationRegistry;

    public AiTaskService(
            AiTaskRepository taskRepository,
            AiTaskPartRepository partRepository,
            AiFileRepository fileRepository,
            AiArtifactRepository artifactRepository,
            AiSessionService sessionService,
            AiTaskEventService eventService,
            AiTaskCancellationRegistry cancellationRegistry) {
        this.taskRepository = taskRepository;
        this.partRepository = partRepository;
        this.fileRepository = fileRepository;
        this.artifactRepository = artifactRepository;
        this.sessionService = sessionService;
        this.eventService = eventService;
        this.cancellationRegistry = cancellationRegistry;
    }

    @Transactional
    public TaskCreation create(String owner, AiTaskCreateRequest request) {
        var idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        var existing = taskRepository.findByOwnerAndIdempotencyKey(owner, idempotencyKey);
        if (existing.isPresent()) {
            return new TaskCreation(toResponse(existing.get()), false);
        }
        var session =
                request.sessionId() == null
                        ? sessionService.createForTask(owner, request.sessionTitle())
                        : sessionService.requireOwned(request.sessionId(), owner);
        var task =
                taskRepository.save(
                        AiTaskEntity.create(
                                owner,
                                session.getId(),
                                request.taskType() == null ? "CHAT" : request.taskType(),
                                request.providerId(),
                                request.model(),
                                idempotencyKey));
        var sequence = 1;
        for (var requested : request.parts()) {
            validatePart(owner, requested);
            partRepository.save(
                    AiTaskPartEntity.create(
                            task.getId(),
                            sequence++,
                            AiPartRole.USER,
                            requested.kind(),
                            requested.text(),
                            null,
                            requested.fileId(),
                            requested.artifactId(),
                            requested.sourceRef()));
            if (requested.fileId() != null) {
                var file = requireReadyFile(owner, requested.fileId());
                file.incrementReference();
                fileRepository.save(file);
            }
        }
        eventService.append(task.getId(), "task.queued", Map.of("status", "QUEUED"));
        return new TaskCreation(toResponse(task), true);
    }

    @Transactional(readOnly = true)
    public AiTaskResponse get(UUID id, String owner) {
        return toResponse(requireOwned(id, owner));
    }

    @Transactional(readOnly = true)
    public List<AiTaskResponse> list(String owner) {
        return taskRepository.findByOwnerOrderByCreatedAtDesc(owner).stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AiTaskPartEntity> parts(UUID id, String owner) {
        requireOwned(id, owner);
        return partRepository.findByTaskIdOrderBySequenceAsc(id);
    }

    @Transactional
    public void start(UUID id, String owner, String providerType, String resolvedModel) {
        var task = requireOwned(id, owner);
        if (task.getStatus() == AiTaskStatus.CANCELLED) return;
        task.start(providerType, resolvedModel);
        taskRepository.save(task);
        eventService.append(
                id, "task.started", Map.of("providerType", providerType, "model", resolvedModel));
    }

    @Transactional
    public void appendAssistantText(UUID id, String owner, String text) {
        var task = requireOwned(id, owner);
        if (task.getStatus() != AiTaskStatus.RUNNING) return;
        var sequence = Math.toIntExact(partRepository.countByTaskId(id) + 1);
        partRepository.save(
                AiTaskPartEntity.create(
                        id,
                        sequence,
                        AiPartRole.ASSISTANT,
                        AiPartKind.TEXT,
                        text,
                        null,
                        null,
                        null,
                        null));
        eventService.append(id, "message.completed", Map.of("content", text));
    }

    @Transactional
    public void complete(UUID id, String owner) {
        var task = requireOwned(id, owner);
        if (task.getStatus() == AiTaskStatus.CANCELLED) return;
        task.complete();
        taskRepository.save(task);
        eventService.append(id, "task.completed", Map.of("status", "COMPLETED"));
    }

    @Transactional
    public void fail(UUID id, String owner, String code, String message) {
        var task = requireOwned(id, owner);
        if (task.getStatus() == AiTaskStatus.CANCELLED) return;
        task.fail(code, message);
        taskRepository.save(task);
        eventService.append(
                id,
                "task.failed",
                Map.of("status", "FAILED", "errorCode", code == null ? "AI_TASK_FAILED" : code));
    }

    @Transactional
    public AiTaskResponse cancel(UUID id, String owner) {
        var task = requireOwned(id, owner);
        var wasTerminal = task.getStatus().isTerminal();
        task.cancel();
        taskRepository.save(task);
        if (!wasTerminal) {
            eventService.append(id, "task.cancelled", Map.of("status", "CANCELLED"));
            cancellationRegistry.cancel(id);
        }
        return toResponse(task);
    }

    @Transactional(readOnly = true)
    public boolean isCancelled(UUID id, String owner) {
        return requireOwned(id, owner).getStatus() == AiTaskStatus.CANCELLED;
    }

    @Transactional(readOnly = true)
    public AiTaskEntity requireOwned(UUID id, String owner) {
        return taskRepository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI task does not exist"));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverInterruptedTasks() {
        var cutoff = Instant.now().minus(Duration.ofMinutes(5));
        for (var task :
                taskRepository.findByStatusInAndUpdatedAtBefore(
                        List.of(AiTaskStatus.RUNNING, AiTaskStatus.WAITING_APPROVAL), cutoff)) {
            task.fail("INTERRUPTED_BY_RESTART", "Task was interrupted by application restart");
            taskRepository.save(task);
            eventService.append(
                    task.getId(),
                    "task.failed",
                    Map.of("status", "FAILED", "errorCode", "INTERRUPTED_BY_RESTART"));
        }
    }

    private void validatePart(String owner, AiTaskPartRequest part) {
        switch (part.kind()) {
            case TEXT -> {
                if (part.text() == null || part.text().isBlank()) {
                    throw new AiServiceException(HttpStatus.BAD_REQUEST, "TEXT part is empty");
                }
            }
            case IMAGE_REF -> {
                var file = requireReadyFile(owner, part.fileId());
                if (!file.getMediaType().startsWith("image/")) {
                    throw new AiServiceException(
                            HttpStatus.BAD_REQUEST, "IMAGE_REF must reference an image");
                }
            }
            case FILE_REF -> requireReadyFile(owner, part.fileId());
            case ARTIFACT_REF -> {
                if (part.artifactId() == null
                        || artifactRepository
                                .findByIdAndOwner(part.artifactId(), owner)
                                .isEmpty()) {
                    throw new NotFoundException("AI artifact does not exist");
                }
            }
            default ->
                    throw new AiServiceException(
                            HttpStatus.BAD_REQUEST,
                            "Client cannot submit tool or source parts in M1");
        }
    }

    private AiFileEntity requireReadyFile(String owner, UUID id) {
        if (id == null) throw new NotFoundException("AI file does not exist");
        var file =
                fileRepository
                        .findByIdAndOwner(id, owner)
                        .orElseThrow(() -> new NotFoundException("AI file does not exist"));
        if (file.getStatus() != AiFileStatus.READY) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI file is not ready");
        }
        return file;
    }

    private AiTaskResponse toResponse(AiTaskEntity task) {
        return AiTaskResponse.from(
                task, partRepository.findByTaskIdOrderBySequenceAsc(task.getId()));
    }

    private static String normalizeIdempotencyKey(String key) {
        return key == null || key.isBlank() ? UUID.randomUUID().toString() : key.trim();
    }

    public record TaskCreation(AiTaskResponse task, boolean created) {}
}
