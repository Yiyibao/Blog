package com.yubai.blog.admin.ai;

import com.yubai.blog.common.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 聊天记录：会话与消息的持久化。
 * 会话归属当前登录账号（owner），标题取该会话第一条用户消息的前十个字。
 */
@Service
public class ChatHistoryService {
    private static final int MAX_SESSION_MESSAGES = 500;
    private static final int TITLE_CHAR_LIMIT = 10;

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;

    public ChatHistoryService(ChatSessionRepository sessionRepository,
                              ChatMessageRepository messageRepository) {
        this.sessionRepository = sessionRepository;
        this.messageRepository = messageRepository;
    }

    @Transactional(readOnly = true)
    public List<ChatSessionResponse> listSessions(String owner) {
        return sessionRepository.findByOwnerOrderByUpdatedAtDesc(owner).stream()
            .map(ChatSessionResponse::from)
            .toList();
    }

    @Transactional
    public ChatSessionResponse createSession(String owner) {
        return ChatSessionResponse.from(sessionRepository.save(ChatSessionEntity.create(owner)));
    }

    @Transactional(readOnly = true)
    public List<ChatMessageResponse> messages(Long sessionId, String owner) {
        var session = requireOwned(sessionId, owner);
        return messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId()).stream()
            .map(ChatMessageResponse::from)
            .toList();
    }

    @Transactional
    public ChatSessionResponse appendMessages(Long sessionId, String owner, List<ChatMessage> messages) {
        var session = requireOwned(sessionId, owner);
        if (session.getTitle() == null) {
            var title = messages.stream()
                .filter(message -> "user".equals(message.role()))
                .map(ChatMessage::content)
                .map(ChatHistoryService::titleFrom)
                .filter(value -> value != null)
                .findFirst()
                .orElse(null);
            if (title != null) session.setTitle(title);
        }
        var existing = messageRepository.countBySessionId(sessionId);
        var room = Math.max(0, MAX_SESSION_MESSAGES - existing);
        for (var message : messages.stream().limit(room).toList()) {
            messageRepository.save(ChatMessageEntity.create(sessionId, message.role(), message.content()));
        }
        return ChatSessionResponse.from(sessionRepository.save(session));
    }

    @Transactional
    public void deleteSession(Long sessionId, String owner) {
        var session = requireOwned(sessionId, owner);
        messageRepository.deleteBySessionId(session.getId());
        sessionRepository.delete(session);
    }

    private ChatSessionEntity requireOwned(Long sessionId, String owner) {
        return sessionRepository.findByIdAndOwner(sessionId, owner)
            .orElseThrow(() -> new NotFoundException("聊天记录不存在"));
    }

    /** 取首条用户消息规范化后的前十个字（按 Unicode 码点，避免截断代理对）。 */
    static String titleFrom(String content) {
        var compact = content == null ? "" : content.replaceAll("\\s+", " ").trim();
        if (compact.isEmpty()) return null;
        var builder = new StringBuilder();
        compact.codePoints().limit(TITLE_CHAR_LIMIT).forEach(builder::appendCodePoint);
        return builder.toString();
    }
}
