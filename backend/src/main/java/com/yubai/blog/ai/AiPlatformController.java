package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.config.AiPlatformProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/ai")
public class AiPlatformController {
    private final AiPlatformProperties properties;
    private final AiSessionService sessionService;
    private final AiTaskService taskService;
    private final AiTaskOrchestrator orchestrator;
    private final AiTaskEventService eventService;
    private final AiFileService fileService;
    private final AiMemoryService memoryService;
    private final AiArtifactService artifactService;

    public AiPlatformController(
            AiPlatformProperties properties,
            AiSessionService sessionService,
            AiTaskService taskService,
            AiTaskOrchestrator orchestrator,
            AiTaskEventService eventService,
            AiFileService fileService,
            AiMemoryService memoryService,
            AiArtifactService artifactService) {
        this.properties = properties;
        this.sessionService = sessionService;
        this.taskService = taskService;
        this.orchestrator = orchestrator;
        this.eventService = eventService;
        this.fileService = fileService;
        this.memoryService = memoryService;
        this.artifactService = artifactService;
    }

    @PostMapping("/sessions")
    public ApiResponse<AiSessionResponse> createSession(
            @Valid @RequestBody AiSessionCreateRequest request, Principal principal) {
        requireTasks();
        return ApiResponse.ok(sessionService.create(principal.getName(), request));
    }

    @GetMapping("/sessions")
    public ApiResponse<List<AiSessionResponse>> sessions(Principal principal) {
        requireTasks();
        return ApiResponse.ok(sessionService.list(principal.getName()));
    }

    @DeleteMapping("/sessions/{sessionId}/summary")
    public ApiResponse<AiSessionResponse> clearSessionSummary(
            @PathVariable Long sessionId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(sessionService.clearSummary(sessionId, principal.getName()));
    }

    @PostMapping("/tasks")
    public ApiResponse<AiTaskResponse> createTask(
            @Valid @RequestBody AiTaskCreateRequest request, Principal principal) {
        requireTasks();
        var creation = taskService.create(principal.getName(), request);
        return ApiResponse.ok(creation.task());
    }

    @PostMapping("/tasks/{taskId}/run")
    public ApiResponse<AiTaskResponse> runTask(@PathVariable UUID taskId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(orchestrator.run(taskId, principal.getName()));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<AiTaskResponse>> tasks(Principal principal) {
        requireTasks();
        return ApiResponse.ok(taskService.list(principal.getName()));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<AiTaskResponse> task(@PathVariable UUID taskId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(taskService.get(taskId, principal.getName()));
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<AiTaskResponse> cancelTask(@PathVariable UUID taskId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(taskService.cancel(taskId, principal.getName()));
    }

    @GetMapping("/tasks/{taskId}/events")
    public ApiResponse<List<AiTaskEventResponse>> taskEvents(
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") long afterSequence,
            Principal principal) {
        requireTasks();
        taskService.requireOwned(taskId, principal.getName());
        return ApiResponse.ok(eventService.replay(taskId, afterSequence));
    }

    @GetMapping(value = "/tasks/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter taskStream(
            @PathVariable UUID taskId,
            @RequestParam(defaultValue = "0") long afterSequence,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
            Principal principal,
            HttpServletResponse response) {
        requireTasks();
        taskService.requireOwned(taskId, principal.getName());
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, private");
        var cursor = Math.max(afterSequence, parseEventId(lastEventId));
        var emitter = new SseEmitter(30_000L);
        try {
            for (var event : eventService.replay(taskId, cursor)) {
                emitter.send(
                        SseEmitter.event()
                                .id(Long.toString(event.sequence()))
                                .name(event.eventType())
                                .data(event));
            }
            emitter.send(SseEmitter.event().comment("replay-complete"));
            emitter.complete();
        } catch (IOException | IllegalStateException exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<AiFileResponse> uploadFile(
            @RequestPart("file") MultipartFile file,
            @RequestParam(required = false) AiFileRetention retention,
            Principal principal) {
        requireMultimodal();
        return ApiResponse.ok(fileService.upload(principal.getName(), file, retention));
    }

    @GetMapping("/files")
    public ApiResponse<List<AiFileResponse>> files(Principal principal) {
        requireMultimodal();
        return ApiResponse.ok(fileService.list(principal.getName()));
    }

    @GetMapping("/files/{fileId}")
    public ApiResponse<AiFileResponse> file(@PathVariable UUID fileId, Principal principal) {
        requireMultimodal();
        return ApiResponse.ok(fileService.get(fileId, principal.getName()));
    }

    @GetMapping("/files/{fileId}/content")
    public ResponseEntity<byte[]> fileContent(@PathVariable UUID fileId, Principal principal) {
        requireMultimodal();
        var content = fileService.readReady(fileId, principal.getName());
        var metadata = content.metadata();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getMediaType()))
                .contentLength(metadata.getSizeBytes())
                .eTag("\"" + metadata.getSha256() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename*=UTF-8''" + encodeFilename(metadata.getOriginalName()))
                .body(content.bytes());
    }

    @DeleteMapping("/files/{fileId}")
    public ApiResponse<Void> deleteFile(@PathVariable UUID fileId, Principal principal) {
        requireMultimodal();
        fileService.delete(fileId, principal.getName());
        return ApiResponse.ok(null);
    }

    @PostMapping("/memories")
    public ApiResponse<AiMemoryResponse> createMemory(
            @Valid @RequestBody AiMemoryCreateRequest request, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.create(principal.getName(), request));
    }

    @GetMapping("/memories")
    public ApiResponse<List<AiMemoryResponse>> memories(Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.list(principal.getName()));
    }

    @GetMapping("/memories/{memoryId}")
    public ApiResponse<AiMemoryResponse> memory(@PathVariable UUID memoryId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.get(memoryId, principal.getName()));
    }

    @PatchMapping("/memories/{memoryId}")
    public ApiResponse<AiMemoryResponse> updateMemory(
            @PathVariable UUID memoryId,
            @Valid @RequestBody AiMemoryUpdateRequest request,
            Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.update(memoryId, principal.getName(), request));
    }

    @PostMapping("/memories/{memoryId}/confirm")
    public ApiResponse<AiMemoryResponse> confirmMemory(
            @PathVariable UUID memoryId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.confirm(memoryId, principal.getName()));
    }

    @PostMapping("/memories/{memoryId}/disable")
    public ApiResponse<AiMemoryResponse> disableMemory(
            @PathVariable UUID memoryId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.disable(memoryId, principal.getName()));
    }

    @PostMapping("/memories/{memoryId}/enable")
    public ApiResponse<AiMemoryResponse> enableMemory(
            @PathVariable UUID memoryId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.enable(memoryId, principal.getName()));
    }

    @PostMapping("/memories/{memoryId}/reject")
    public ApiResponse<AiMemoryResponse> rejectMemory(
            @PathVariable UUID memoryId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.reject(memoryId, principal.getName()));
    }

    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<Void> forgetMemory(@PathVariable UUID memoryId, Principal principal) {
        requireMemory();
        memoryService.forget(memoryId, principal.getName());
        return ApiResponse.ok(null);
    }

    @PostMapping("/tasks/{taskId}/artifacts")
    public ApiResponse<AiArtifactResponse> createArtifact(
            @PathVariable UUID taskId,
            @Valid @RequestBody AiArtifactCreateRequest request,
            Principal principal) {
        requireArtifacts();
        return ApiResponse.ok(artifactService.create(taskId, principal.getName(), request));
    }

    @GetMapping("/artifacts")
    public ApiResponse<List<AiArtifactResponse>> artifacts(Principal principal) {
        requireArtifacts();
        return ApiResponse.ok(artifactService.list(principal.getName()));
    }

    @GetMapping("/tasks/{taskId}/artifacts")
    public ApiResponse<List<AiArtifactResponse>> taskArtifacts(
            @PathVariable UUID taskId, Principal principal) {
        requireArtifacts();
        return ApiResponse.ok(artifactService.listForTask(taskId, principal.getName()));
    }

    @GetMapping("/artifacts/{artifactId}/download")
    public ResponseEntity<byte[]> downloadArtifact(
            @PathVariable UUID artifactId, Principal principal) {
        requireArtifacts();
        var content = artifactService.read(artifactId, principal.getName());
        var metadata = content.metadata();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(metadata.getMediaType()))
                .contentLength(metadata.getSizeBytes())
                .eTag("\"" + metadata.getSha256() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodeFilename(metadata.getName()))
                .body(content.bytes());
    }

    @DeleteMapping("/artifacts/{artifactId}")
    public ApiResponse<Void> deleteArtifact(@PathVariable UUID artifactId, Principal principal) {
        requireArtifacts();
        artifactService.delete(artifactId, principal.getName());
        return ApiResponse.ok(null);
    }

    private void requireTasks() {
        require(properties.isTasksEnabled());
    }

    private void requireMultimodal() {
        require(properties.isTasksEnabled() && properties.isMultimodalEnabled());
    }

    private void requireMemory() {
        require(properties.isTasksEnabled() && properties.isMemoryEnabled());
    }

    private void requireArtifacts() {
        require(properties.isTasksEnabled() && properties.isArtifactsEnabled());
    }

    private static void require(boolean enabled) {
        if (!enabled) {
            throw new AiServiceException(
                    HttpStatus.NOT_FOUND, "AI platform capability is disabled");
        }
    }

    private static long parseEventId(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            return Math.max(0, Long.parseLong(value.trim()));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String encodeFilename(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
