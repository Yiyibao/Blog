package com.yubai.blog.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiSessionConversationService {
    private final AiSessionService sessionService;
    private final AiTaskRepository taskRepository;
    private final AiTaskPartRepository partRepository;

    public AiSessionConversationService(
            AiSessionService sessionService,
            AiTaskRepository taskRepository,
            AiTaskPartRepository partRepository) {
        this.sessionService = sessionService;
        this.taskRepository = taskRepository;
        this.partRepository = partRepository;
    }

    @Transactional(readOnly = true)
    public AiSessionConversationResponse get(
            Long sessionId, String owner, int requestedPage, int requestedSize) {
        var session = sessionService.requireOwned(sessionId, owner);
        var page = Math.max(0, requestedPage);
        var size = Math.min(100, Math.max(1, requestedSize));
        var taskPage =
                taskRepository.findByOwnerAndSessionIdOrderByCreatedAtDesc(
                        owner, sessionId, PageRequest.of(page, size + 1));
        var hasMore = taskPage.size() > size;
        var tasks =
                hasMore ? new ArrayList<>(taskPage.subList(0, size)) : new ArrayList<>(taskPage);
        var taskIds = tasks.stream().map(AiTaskEntity::getId).toList();
        var parts =
                taskIds.isEmpty()
                        ? List.<AiTaskPartEntity>of()
                        : partRepository.findByTaskIdInOrderByCreatedAtAsc(taskIds);
        var taskOrder =
                tasks.stream()
                        .map(AiTaskEntity::getId)
                        .collect(java.util.stream.Collectors.toMap(id -> id, taskIds::indexOf));
        var messages =
                parts.stream()
                        .sorted(
                                Comparator.comparingInt(
                                                (AiTaskPartEntity part) ->
                                                        taskOrder.getOrDefault(part.getTaskId(), 0))
                                        .thenComparing(AiTaskPartEntity::getSequence))
                        .map(AiConversationMessageResponse::from)
                        .toList();
        return new AiSessionConversationResponse(
                AiSessionResponse.from(session), messages, hasMore, page, size);
    }
}
