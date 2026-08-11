package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.config.AiPlatformProperties;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai")
public class AiProjectController {
    private final AiProjectService projectService;
    private final AiSessionService sessionService;
    private final AiTaskService taskService;
    private final AiMemoryService memoryService;
    private final AiSessionConversationService conversationService;
    private final AiPlatformProperties properties;

    public AiProjectController(
            AiProjectService projectService,
            AiSessionService sessionService,
            AiTaskService taskService,
            AiMemoryService memoryService,
            AiSessionConversationService conversationService,
            AiPlatformProperties properties) {
        this.projectService = projectService;
        this.sessionService = sessionService;
        this.taskService = taskService;
        this.memoryService = memoryService;
        this.conversationService = conversationService;
        this.properties = properties;
    }

    @PostMapping("/projects")
    public ApiResponse<AiProjectResponse> createProject(
            @Valid @RequestBody AiProjectCreateRequest request, Principal principal) {
        requireTasks();
        return ApiResponse.ok(projectService.create(principal.getName(), request));
    }

    @GetMapping("/projects")
    public ApiResponse<List<AiProjectResponse>> projects(Principal principal) {
        requireTasks();
        return ApiResponse.ok(projectService.list(principal.getName()));
    }

    @PatchMapping("/projects/{projectId}")
    public ApiResponse<AiProjectResponse> renameProject(
            @PathVariable Long projectId,
            @Valid @RequestBody AiProjectUpdateRequest request,
            Principal principal) {
        requireTasks();
        return ApiResponse.ok(projectService.rename(projectId, principal.getName(), request));
    }

    @PostMapping("/projects/{projectId}/archive")
    public ApiResponse<AiProjectResponse> archiveProject(
            @PathVariable Long projectId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(projectService.archive(projectId, principal.getName()));
    }

    @PostMapping("/projects/{projectId}/restore")
    public ApiResponse<AiProjectResponse> restoreProject(
            @PathVariable Long projectId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(projectService.restore(projectId, principal.getName()));
    }

    @GetMapping("/projects/{projectId}/sessions")
    public ApiResponse<List<AiSessionResponse>> projectSessions(
            @PathVariable Long projectId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(sessionService.listByProject(principal.getName(), projectId));
    }

    @GetMapping("/projects/{projectId}/tasks")
    public ApiResponse<List<AiTaskResponse>> projectTasks(
            @PathVariable Long projectId, Principal principal) {
        requireTasks();
        projectService.requireOwned(projectId, principal.getName());
        return ApiResponse.ok(taskService.listByProject(principal.getName(), projectId));
    }

    @GetMapping("/projects/{projectId}/memories")
    public ApiResponse<List<AiMemoryResponse>> projectMemories(
            @PathVariable Long projectId, Principal principal) {
        requireMemory();
        return ApiResponse.ok(memoryService.listByProject(principal.getName(), projectId));
    }

    @PatchMapping("/sessions/{sessionId}")
    public ApiResponse<AiSessionResponse> updateSession(
            @PathVariable Long sessionId,
            @Valid @RequestBody AiSessionUpdateRequest request,
            Principal principal) {
        requireTasks();
        return ApiResponse.ok(sessionService.update(sessionId, principal.getName(), request));
    }

    @PostMapping("/sessions/{sessionId}/move")
    public ApiResponse<AiSessionResponse> moveSession(
            @PathVariable Long sessionId,
            @RequestBody(required = false) AiSessionMoveRequest request,
            Principal principal) {
        requireTasks();
        return ApiResponse.ok(
                sessionService.move(
                        sessionId,
                        principal.getName(),
                        request == null ? null : request.projectId()));
    }

    @PostMapping("/sessions/{sessionId}/archive")
    public ApiResponse<AiSessionResponse> archiveSession(
            @PathVariable Long sessionId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(sessionService.archive(sessionId, principal.getName()));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ApiResponse<AiSessionResponse> deleteSession(
            @PathVariable Long sessionId, Principal principal) {
        requireTasks();
        return ApiResponse.ok(sessionService.delete(sessionId, principal.getName()));
    }

    @GetMapping("/sessions/{sessionId}/conversation")
    public ApiResponse<AiSessionConversationResponse> conversation(
            @PathVariable Long sessionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "40") int size,
            Principal principal) {
        requireTasks();
        return ApiResponse.ok(conversationService.get(sessionId, principal.getName(), page, size));
    }

    public record AiSessionMoveRequest(Long projectId) {}

    private void requireTasks() {
        if (!properties.isTasksEnabled()) {
            throw new AiServiceException(
                    HttpStatus.NOT_FOUND, "AI platform capability is disabled");
        }
    }

    private void requireMemory() {
        if (!properties.isTasksEnabled() || !properties.isMemoryEnabled()) {
            throw new AiServiceException(HttpStatus.NOT_FOUND, "AI memory capability is disabled");
        }
    }
}
