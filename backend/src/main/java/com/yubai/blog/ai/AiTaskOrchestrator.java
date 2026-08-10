package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.TooManyRequestsException;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AiTaskOrchestrator {
    private final AiTaskService taskService;
    private final AiContextWindowService contextWindowService;
    private final AiModelGateway modelGateway;
    private final AiTaskEventService eventService;
    private final AiTaskConcurrencyGuard concurrencyGuard;
    private final AiTaskCancellationRegistry cancellationRegistry;

    public AiTaskOrchestrator(
            AiTaskService taskService,
            AiContextWindowService contextWindowService,
            AiModelGateway modelGateway,
            AiTaskEventService eventService,
            AiTaskConcurrencyGuard concurrencyGuard,
            AiTaskCancellationRegistry cancellationRegistry) {
        this.taskService = taskService;
        this.contextWindowService = contextWindowService;
        this.modelGateway = modelGateway;
        this.eventService = eventService;
        this.concurrencyGuard = concurrencyGuard;
        this.cancellationRegistry = cancellationRegistry;
    }

    public AiTaskResponse run(UUID taskId, String owner) {
        var task = taskService.requireOwned(taskId, owner);
        if (task.getStatus().isTerminal()) return taskService.get(taskId, owner);
        if (task.getStatus() != AiTaskStatus.QUEUED) {
            throw new AiServiceException(
                    org.springframework.http.HttpStatus.CONFLICT, "AI task is already running");
        }
        try (var lease = concurrencyGuard.acquire(owner, task.getProviderId())) {
            cancellationRegistry.register(taskId);
            if (taskService.isCancelled(taskId, owner)) return taskService.get(taskId, owner);
            var context = contextWindowService.load(owner, task.getSessionId());
            if (context.truncated()) {
                eventService.append(
                        taskId,
                        "context.truncated",
                        Map.of(
                                "memory", context.memoryTruncated(),
                                "summary", context.summaryTruncated(),
                                "recentMessages", context.recentMessagesTruncated()));
            }
            var prepared =
                    modelGateway.prepare(
                            owner,
                            task.getProviderId(),
                            task.getModel(),
                            context,
                            taskService.parts(taskId, owner));
            taskService.start(
                    taskId,
                    owner,
                    prepared.endpoint().providerType().name(),
                    prepared.endpoint().model());
            var result = modelGateway.execute(prepared);
            if (Thread.currentThread().isInterrupted() || taskService.isCancelled(taskId, owner)) {
                return taskService.get(taskId, owner);
            }
            eventService.append(taskId, "message.delta", Map.of("content", result.text()));
            taskService.appendAssistantText(taskId, owner, result.text());
            taskService.complete(taskId, owner);
            contextWindowService.refreshSummary(owner, task.getSessionId());
        } catch (TooManyRequestsException exception) {
            taskService.fail(taskId, owner, "AI_QUEUE_FULL", exception.getMessage());
            throw exception;
        } catch (AiServiceException exception) {
            taskService.fail(
                    taskId,
                    owner,
                    "AI_PROVIDER_ERROR_" + exception.getStatus().value(),
                    exception.getMessage());
        } catch (RuntimeException exception) {
            taskService.fail(taskId, owner, "AI_TASK_FAILED", "AI task failed");
        } finally {
            cancellationRegistry.unregister(taskId);
            Thread.interrupted();
        }
        return taskService.get(taskId, owner);
    }
}
