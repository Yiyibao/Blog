package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiTaskRecoveryTest {
    @Test
    void staleRunningTaskBecomesExplicitFailedTerminalAfterRestart() {
        var taskRepository = mock(AiTaskRepository.class);
        var eventService = mock(AiTaskEventService.class);
        var task = AiTaskEntity.create("alice", 1L, "CHAT", null, null, "restart-case");
        task.start("OPENAI_RESPONSES", "fake-model");
        when(taskRepository.findByStatusInAndUpdatedAtBefore(any(), any()))
                .thenReturn(List.of(task));
        var service =
                new AiTaskService(
                        taskRepository,
                        mock(AiTaskPartRepository.class),
                        mock(AiFileRepository.class),
                        mock(AiArtifactRepository.class),
                        mock(AiSessionService.class),
                        eventService,
                        mock(AiTaskCancellationRegistry.class));

        service.recoverInterruptedTasks();

        assertThat(task.getStatus()).isEqualTo(AiTaskStatus.FAILED);
        assertThat(task.getErrorCode()).isEqualTo("INTERRUPTED_BY_RESTART");
        verify(taskRepository).save(task);
        verify(eventService)
                .append(
                        task.getId(),
                        "task.failed",
                        Map.of("status", "FAILED", "errorCode", "INTERRUPTED_BY_RESTART"));
    }
}
