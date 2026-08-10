package com.yubai.blog.ai;

import com.yubai.blog.common.NotFoundException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiSessionService {
    private final AiSessionRepository repository;

    public AiSessionService(AiSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AiSessionResponse create(String owner, AiSessionCreateRequest request) {
        return AiSessionResponse.from(
                repository.save(AiSessionEntity.create(owner, request.title(), request.mode())));
    }

    @Transactional(readOnly = true)
    public List<AiSessionResponse> list(String owner) {
        return repository.findByOwnerOrderByUpdatedAtDesc(owner).stream()
                .map(AiSessionResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AiSessionEntity requireOwned(Long id, String owner) {
        return repository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI session does not exist"));
    }

    @Transactional
    public AiSessionEntity createForTask(String owner, String title) {
        return repository.save(AiSessionEntity.create(owner, title, "WORKSPACE"));
    }

    @Transactional
    public AiSessionResponse clearSummary(Long id, String owner) {
        var session = requireOwned(id, owner);
        session.updateSummary(null);
        return AiSessionResponse.from(repository.save(session));
    }
}
