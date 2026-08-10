package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.config.AiPlatformProperties;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiContextWindowServiceTest {
    @Test
    void loadsSummaryAndRecentMessagesInChronologicalOrder() {
        var memories = mock(AiMemoryService.class);
        var sessions = mock(AiSessionRepository.class);
        var parts = mock(AiTaskPartRepository.class);
        var properties = new AiPlatformProperties();
        properties.setMaxRecentMessages(2);
        var session = AiSessionEntity.create("alice", "memory", "WORKSPACE");
        session.updateSummary("older summary");
        when(sessions.findByIdAndOwner(7L, "alice")).thenReturn(Optional.of(session));
        when(memories.activeForContext("alice", 7L)).thenReturn(List.of());
        when(parts.findSessionPartsNewestFirst(
                        eq("alice"),
                        eq(7L),
                        eq(AiTaskStatus.COMPLETED),
                        eq(AiPartKind.TEXT),
                        any()))
                .thenReturn(
                        List.of(
                                text(AiPartRole.ASSISTANT, "latest answer"),
                                text(AiPartRole.USER, "latest question"),
                                text(AiPartRole.ASSISTANT, "older answer")));
        var service = new AiContextWindowService(memories, sessions, parts, properties);

        var context = service.load("alice", 7L);

        assertThat(context.sessionSummary()).isEqualTo("older summary");
        assertThat(context.recentMessages())
                .extracting(AiTaskPartEntity::getTextContent)
                .containsExactly("latest question", "latest answer");
        assertThat(context.recentMessagesTruncated()).isTrue();
    }

    @Test
    void refreshesServerSummaryFromMessagesOutsideRecentWindow() {
        var memories = mock(AiMemoryService.class);
        var sessions = mock(AiSessionRepository.class);
        var parts = mock(AiTaskPartRepository.class);
        var properties = new AiPlatformProperties();
        properties.setMaxRecentMessages(2);
        properties.setMaxSummarySourceMessages(10);
        var session = AiSessionEntity.create("alice", "memory", "WORKSPACE");
        when(sessions.findByIdAndOwner(7L, "alice")).thenReturn(Optional.of(session));
        when(parts.findSessionPartsNewestFirst(
                        eq("alice"),
                        eq(7L),
                        eq(AiTaskStatus.COMPLETED),
                        eq(AiPartKind.TEXT),
                        any()))
                .thenReturn(
                        List.of(
                                text(AiPartRole.ASSISTANT, "latest answer"),
                                text(AiPartRole.USER, "latest question"),
                                text(AiPartRole.ASSISTANT, "remembered answer"),
                                text(AiPartRole.USER, "remembered question")));
        var service = new AiContextWindowService(memories, sessions, parts, properties);

        service.refreshSummary("alice", 7L);

        assertThat(session.getSummary())
                .contains("User: remembered question")
                .contains("Assistant: remembered answer")
                .doesNotContain("latest question");
        verify(sessions).save(session);
    }

    private static AiTaskPartEntity text(AiPartRole role, String content) {
        return AiTaskPartEntity.create(
                UUID.randomUUID(), 1, role, AiPartKind.TEXT, content, null, null, null, null);
    }
}
