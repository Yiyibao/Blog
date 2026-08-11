package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.yubai.blog.admin.ai.AiServiceException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AiTaskServiceTest {
    @Test
    void rejectsAProjectTaskWhenItsSessionBelongsToAnotherProject() {
        var taskRepository = mock(AiTaskRepository.class);
        var sessionService = mock(AiSessionService.class);
        var session = AiSessionEntity.create("alice", "project task", "WORKSPACE", 7L);
        org.mockito.Mockito.when(sessionService.requireOwned(11L, "alice")).thenReturn(session);
        var service =
                new AiTaskService(
                        taskRepository,
                        mock(AiTaskPartRepository.class),
                        mock(AiFileRepository.class),
                        mock(AiArtifactRepository.class),
                        sessionService,
                        mock(AiTaskEventService.class),
                        mock(AiTaskCancellationRegistry.class));

        assertThatThrownBy(
                        () ->
                                service.create(
                                        "alice",
                                        new AiTaskCreateRequest(
                                                11L,
                                                9L,
                                                "project task",
                                                "CHAT",
                                                null,
                                                null,
                                                null,
                                                "cross-project",
                                                List.of(
                                                        new AiTaskPartRequest(
                                                                AiPartKind.TEXT,
                                                                "hello",
                                                                null,
                                                                null,
                                                                null)))))
                .isInstanceOf(AiServiceException.class)
                .hasMessageContaining("does not match");
        verify(taskRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
