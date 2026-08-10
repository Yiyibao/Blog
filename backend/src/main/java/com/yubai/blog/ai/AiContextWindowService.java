package com.yubai.blog.ai;

import com.yubai.blog.config.AiPlatformProperties;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiContextWindowService {
    private final AiMemoryService memoryService;
    private final AiSessionRepository sessionRepository;
    private final AiTaskPartRepository partRepository;
    private final AiPlatformProperties properties;

    public AiContextWindowService(
            AiMemoryService memoryService,
            AiSessionRepository sessionRepository,
            AiTaskPartRepository partRepository,
            AiPlatformProperties properties) {
        this.memoryService = memoryService;
        this.sessionRepository = sessionRepository;
        this.partRepository = partRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public AiContextWindow load(String owner, Long sessionId) {
        var session =
                sessionRepository
                        .findByIdAndOwner(sessionId, owner)
                        .orElseThrow(
                                () ->
                                        new com.yubai.blog.common.NotFoundException(
                                                "AI session does not exist"));
        var allMemories = memoryService.activeForContext(owner, sessionId);
        var selectedMemories =
                takeMemories(allMemories, charsForTokens(properties.getMaxMemoryContextTokens()));
        var summary =
                truncate(
                        session.getSummary(),
                        charsForTokens(properties.getMaxSessionSummaryTokens()));

        var maxMessages = Math.max(1, properties.getMaxRecentMessages());
        var newest =
                partRepository.findSessionPartsNewestFirst(
                        owner,
                        sessionId,
                        AiTaskStatus.COMPLETED,
                        AiPartKind.TEXT,
                        PageRequest.of(0, maxMessages + 1));
        var hadMoreMessages = newest.size() > maxMessages;
        if (hadMoreMessages) newest = new ArrayList<>(newest.subList(0, maxMessages));
        var selectedMessages =
                takeNewestMessages(newest, charsForTokens(properties.getMaxRecentMessageTokens()));

        return new AiContextWindow(
                selectedMemories.values(),
                summary.value(),
                selectedMessages.values(),
                selectedMemories.truncated(),
                summary.truncated(),
                hadMoreMessages || selectedMessages.truncated());
    }

    @Transactional
    public void refreshSummary(String owner, Long sessionId) {
        var session =
                sessionRepository
                        .findByIdAndOwner(sessionId, owner)
                        .orElseThrow(
                                () ->
                                        new com.yubai.blog.common.NotFoundException(
                                                "AI session does not exist"));
        var recentCount = Math.max(1, properties.getMaxRecentMessages());
        var sourceLimit = Math.max(recentCount + 1, properties.getMaxSummarySourceMessages());
        var newest =
                partRepository.findSessionPartsNewestFirst(
                        owner,
                        sessionId,
                        AiTaskStatus.COMPLETED,
                        AiPartKind.TEXT,
                        PageRequest.of(0, sourceLimit + 1));
        if (newest.size() <= recentCount) {
            session.updateSummary(null);
            sessionRepository.save(session);
            return;
        }

        var sourceWasTruncated = newest.size() > sourceLimit;
        var olderNewestFirst =
                new ArrayList<>(newest.subList(recentCount, Math.min(newest.size(), sourceLimit)));
        Collections.reverse(olderNewestFirst);
        var summary = new StringBuilder();
        if (sourceWasTruncated) summary.append("[Earlier conversation was compacted]\n");
        for (var message : olderNewestFirst) {
            var role = message.getRole() == AiPartRole.ASSISTANT ? "Assistant" : "User";
            var text = normalize(message.getTextContent());
            if (text.isBlank()) continue;
            summary.append(role).append(": ").append(text).append('\n');
        }
        session.updateSummary(
                truncate(
                                summary.toString(),
                                charsForTokens(properties.getMaxSessionSummaryTokens()))
                        .value());
        sessionRepository.save(session);
    }

    private static Selection<AiMemoryEntity> takeMemories(
            List<AiMemoryEntity> memories, int maxChars) {
        var selected = new ArrayList<AiMemoryEntity>();
        var used = 0;
        for (var memory : memories) {
            var content = memory.getContent();
            if (content == null || content.isBlank()) continue;
            var cost = content.length() + memory.getKind().length() + 8;
            if (used + cost > maxChars) continue;
            selected.add(memory);
            used += cost;
        }
        return new Selection<>(List.copyOf(selected), selected.size() < memories.size());
    }

    private static Selection<AiTaskPartEntity> takeNewestMessages(
            List<AiTaskPartEntity> newestFirst, int maxChars) {
        var selected = new ArrayList<AiTaskPartEntity>();
        var used = 0;
        for (var message : newestFirst) {
            var text = message.getTextContent();
            if (text == null || text.isBlank()) continue;
            var cost = text.length() + 16;
            if (used + cost > maxChars) break;
            selected.add(message);
            used += cost;
        }
        var truncated = selected.size() < newestFirst.size();
        Collections.reverse(selected);
        return new Selection<>(List.copyOf(selected), truncated);
    }

    private static TruncatedText truncate(String value, int maxChars) {
        if (value == null || value.isBlank()) return new TruncatedText(null, false);
        var normalized = value.trim();
        if (normalized.length() <= maxChars) return new TruncatedText(normalized, false);
        return new TruncatedText(normalized.substring(normalized.length() - maxChars), true);
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.replaceAll("\\s+", " ").trim();
    }

    private static int charsForTokens(int tokens) {
        // One Unicode code unit per token deliberately under-fills English context while staying
        // safe for CJK text until provider-specific tokenizers are introduced.
        return Math.max(1, tokens);
    }

    private record Selection<T>(List<T> values, boolean truncated) {}

    private record TruncatedText(String value, boolean truncated) {}
}
