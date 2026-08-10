package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.admin.ai.AiServiceException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiMemoryServiceTest {
    @Test
    void sourceTaskCreatesProposalAndRequiresConfirmation() {
        var memories = mock(AiMemoryRepository.class);
        var tasks = mock(AiTaskRepository.class);
        var sourceTaskId = UUID.randomUUID();
        var task = AiTaskEntity.create("alice", 1L, "CHAT", null, null, "key");
        when(tasks.findByIdAndOwner(sourceTaskId, "alice")).thenReturn(Optional.of(task));
        when(memories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service = new AiMemoryService(memories, tasks, mock(AiSessionRepository.class));

        var created =
                service.create(
                        "alice",
                        new AiMemoryCreateRequest(
                                "USER", "PREFERENCE", "喜欢简洁回答", sourceTaskId, null, null, null));

        assertThat(created.status()).isEqualTo(AiMemoryStatus.PROPOSED);
    }

    @Test
    void directUserMemoryIsActiveButSensitiveMemoryIsRejected() {
        var memories = mock(AiMemoryRepository.class);
        when(memories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var service =
                new AiMemoryService(
                        memories, mock(AiTaskRepository.class), mock(AiSessionRepository.class));

        var active =
                service.create(
                        "alice",
                        new AiMemoryCreateRequest(
                                "USER", "PREFERENCE", "回答使用中文", null, null, null, null));

        assertThat(active.status()).isEqualTo(AiMemoryStatus.ACTIVE);
        assertThatThrownBy(
                        () ->
                                service.create(
                                        "alice",
                                        new AiMemoryCreateRequest(
                                                "USER",
                                                "SECRET",
                                                "token is abc",
                                                null,
                                                null,
                                                null,
                                                null)))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void activeRecallIncludesGlobalAndMatchingSessionScopesOnly() {
        var memories = mock(AiMemoryRepository.class);
        var global =
                AiMemoryEntity.create(
                        "alice",
                        "USER",
                        "PREFERENCE",
                        "回答使用中文",
                        null,
                        null,
                        AiMemoryStatus.ACTIVE,
                        null,
                        null);
        var matching =
                AiMemoryEntity.create(
                        "alice",
                        "SESSION:7",
                        "GOAL",
                        "完成当前文章",
                        null,
                        null,
                        AiMemoryStatus.ACTIVE,
                        null,
                        null);
        var other =
                AiMemoryEntity.create(
                        "alice",
                        "SESSION:8",
                        "GOAL",
                        "另一个会话",
                        null,
                        null,
                        AiMemoryStatus.ACTIVE,
                        null,
                        null);
        when(memories.findActiveForContext(any(), any(), any(), any()))
                .thenReturn(List.of(global, matching, other));
        var service =
                new AiMemoryService(
                        memories, mock(AiTaskRepository.class), mock(AiSessionRepository.class));

        assertThat(service.activeForContext("alice", 7L))
                .extracting(AiMemoryEntity::getContent)
                .containsExactly("回答使用中文", "完成当前文章");
    }

    @Test
    void forgettingClearsBodyAndDerivedSessionSummaries() {
        var memories = mock(AiMemoryRepository.class);
        var sessions = mock(AiSessionRepository.class);
        var memory =
                AiMemoryEntity.create(
                        "alice",
                        "USER",
                        "PREFERENCE",
                        "回答使用中文",
                        null,
                        null,
                        AiMemoryStatus.ACTIVE,
                        null,
                        null);
        var session = AiSessionEntity.create("alice", "memory", "WORKSPACE");
        session.updateSummary("derived context");
        when(memories.findByIdAndOwner(memory.getId(), "alice")).thenReturn(Optional.of(memory));
        when(memories.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(sessions.findByOwnerOrderByUpdatedAtDesc("alice")).thenReturn(List.of(session));
        var service = new AiMemoryService(memories, mock(AiTaskRepository.class), sessions);

        service.forget(memory.getId(), "alice");

        assertThat(memory.getStatus()).isEqualTo(AiMemoryStatus.DELETED);
        assertThat(memory.getContent()).isNull();
        assertThat(session.getSummary()).isNull();
        verify(sessions).save(session);
    }
}
