package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubai.blog.common.NotFoundException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChatHistoryServiceTest {

    private ChatSessionRepository sessionRepository;
    private ChatMessageRepository messageRepository;
    private ChatHistoryService service;

    @BeforeEach
    void setUp() {
        sessionRepository = mock(ChatSessionRepository.class);
        messageRepository = mock(ChatMessageRepository.class);
        service = new ChatHistoryService(sessionRepository, messageRepository);
    }

    private static ChatSessionEntity session(Long id, String owner, String title) {
        var entity = ChatSessionEntity.create(owner);
        if (title != null) entity.setTitle(title);
        try {
            var idField = ChatSessionEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, id);
        } catch (ReflectiveOperationException ignored) {
            // test-only id injection
        }
        return entity;
    }

    @Test
    void listSessionsOrdersByUpdatedAtDesc() {
        when(sessionRepository.findByOwnerOrderByUpdatedAtDesc("admin"))
            .thenReturn(List.of(session(2L, "admin", "标题二"), session(1L, "admin", null)));

        var sessions = service.listSessions("admin");

        assertEquals(2, sessions.size());
        assertEquals(2L, sessions.get(0).id());
        assertEquals("标题二", sessions.get(0).title());
        assertNull(sessions.get(1).title());
    }

    @Test
    void createSessionStoresOwner() {
        var created = session(7L, "admin", null);
        when(sessionRepository.save(any(ChatSessionEntity.class))).thenReturn(created);

        var response = service.createSession("admin");

        assertEquals(7L, response.id());
        verify(sessionRepository).save(any(ChatSessionEntity.class));
    }

    @Test
    void titleComesFromFirstUserMessageFirstTenChars() {
        var session = session(1L, "admin", null);
        when(sessionRepository.findByIdAndOwner(1L, "admin")).thenReturn(Optional.of(session));
        when(messageRepository.countBySessionId(1L)).thenReturn(0L);
        when(sessionRepository.save(any(ChatSessionEntity.class))).thenReturn(session);

        var updated = service.appendMessages(1L, "admin", List.of(
            new ChatMessage("user", "今天天气真不错，我们一起去西湖边散步吧"),
            new ChatMessage("assistant", "好的，出发！")));

        assertEquals("今天天气真不错，我们", updated.title());
    }

    @Test
    void existingTitleIsKept() {
        var session = session(1L, "admin", "已有标题");
        when(sessionRepository.findByIdAndOwner(1L, "admin")).thenReturn(Optional.of(session));
        when(messageRepository.countBySessionId(1L)).thenReturn(0L);
        when(sessionRepository.save(any(ChatSessionEntity.class))).thenReturn(session);

        var updated = service.appendMessages(1L, "admin",
            List.of(new ChatMessage("user", "新消息")));

        assertEquals("已有标题", updated.title());
    }

    @Test
    void appendMessagesPersistsExchange() {
        var session = session(1L, "admin", null);
        when(sessionRepository.findByIdAndOwner(1L, "admin")).thenReturn(Optional.of(session));
        when(messageRepository.countBySessionId(1L)).thenReturn(0L);
        when(sessionRepository.save(any(ChatSessionEntity.class))).thenReturn(session);

        service.appendMessages(1L, "admin", List.of(
            new ChatMessage("user", "你好"),
            new ChatMessage("assistant", "你好呀")));

        verify(messageRepository, org.mockito.Mockito.times(2))
            .save(any(ChatMessageEntity.class));
    }

    @Test
    void appendMessagesCapsSessionAtMax() {
        var session = session(1L, "admin", "标题");
        when(sessionRepository.findByIdAndOwner(1L, "admin")).thenReturn(Optional.of(session));
        when(messageRepository.countBySessionId(1L)).thenReturn(499L);
        when(sessionRepository.save(any(ChatSessionEntity.class))).thenReturn(session);

        service.appendMessages(1L, "admin", List.of(
            new ChatMessage("user", "a"),
            new ChatMessage("assistant", "b")));

        verify(messageRepository, org.mockito.Mockito.times(1))
            .save(any(ChatMessageEntity.class));
    }

    @Test
    void messagesOfForeignOwnerAreHidden() {
        when(sessionRepository.findByIdAndOwner(1L, "xinn")).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> service.messages(1L, "xinn"));
        verify(messageRepository, never()).findBySessionIdOrderByCreatedAtAsc(anyLong());
    }

    @Test
    void deleteSessionRemovesMessagesAndSession() {
        var session = session(1L, "admin", "标题");
        when(sessionRepository.findByIdAndOwner(1L, "admin")).thenReturn(Optional.of(session));

        service.deleteSession(1L, "admin");

        verify(messageRepository).deleteBySessionId(1L);
        verify(sessionRepository).delete(session);
    }

    @Test
    void titleFromTrimsWhitespaceAndStopsAtTenCodePoints() {
        assertEquals("你好世界你好世界你好", ChatHistoryService.titleFrom("  你好世界你好世界你好世界  "));
        assertEquals("ab cd ef g", ChatHistoryService.titleFrom("ab cd ef gh ij kl"));
        assertNull(ChatHistoryService.titleFrom("   "));
    }
}
