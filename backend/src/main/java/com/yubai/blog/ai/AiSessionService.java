package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiSessionService {
    private final AiSessionRepository repository;
    private final AiProjectService projectService;

    public AiSessionService(AiSessionRepository repository, AiProjectService projectService) {
        this.repository = repository;
        this.projectService = projectService;
    }

    @Transactional
    public AiSessionResponse create(String owner, AiSessionCreateRequest request) {
        return AiSessionResponse.from(
                repository.save(
                        AiSessionEntity.create(
                                owner,
                                request.title(),
                                request.mode(),
                                checkedProject(owner, request.projectId()))));
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
        return createForTask(owner, title, null);
    }

    @Transactional
    public AiSessionEntity createForTask(String owner, String title, Long projectId) {
        return repository.save(
                AiSessionEntity.create(
                        owner, title, "WORKSPACE", checkedProject(owner, projectId)));
    }

    @Transactional
    public AiSessionResponse clearSummary(Long id, String owner) {
        var session = requireOwned(id, owner);
        session.updateSummary(null);
        return AiSessionResponse.from(repository.save(session));
    }

    @Transactional
    public AiSessionResponse update(Long id, String owner, AiSessionUpdateRequest request) {
        var session = requireOwned(id, owner);
        if (session.getVersion() != request.version()) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI session version conflict");
        }
        try {
            var previousProjectId = session.getProjectId();
            if (request.title() != null) session.updateTitle(request.title());
            session.moveToProject(checkedProject(owner, request.projectId()));
            if (!java.util.Objects.equals(previousProjectId, session.getProjectId())) {
                session.updateSummary(null);
            }
        } catch (IllegalArgumentException exception) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IllegalStateException exception) {
            throw new AiServiceException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return AiSessionResponse.from(repository.save(session));
    }

    @Transactional
    public AiSessionResponse move(Long id, String owner, Long projectId) {
        var session = requireOwned(id, owner);
        try {
            var previousProjectId = session.getProjectId();
            session.moveToProject(checkedProject(owner, projectId));
            if (!java.util.Objects.equals(previousProjectId, session.getProjectId())) {
                session.updateSummary(null);
            }
        } catch (IllegalStateException exception) {
            throw new AiServiceException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return AiSessionResponse.from(repository.save(session));
    }

    @Transactional
    public AiSessionResponse archive(Long id, String owner) {
        var session = requireOwned(id, owner);
        session.archive();
        return AiSessionResponse.from(repository.save(session));
    }

    @Transactional
    public AiSessionResponse delete(Long id, String owner) {
        var session = requireOwned(id, owner);
        session.delete();
        return AiSessionResponse.from(repository.save(session));
    }

    @Transactional(readOnly = true)
    public List<AiSessionResponse> listByProject(String owner, Long projectId) {
        projectService.requireOwned(projectId, owner);
        return repository
                .findByOwnerAndProjectIdAndStatusNotOrderByUpdatedAtDesc(
                        owner, projectId, AiSessionStatus.DELETED)
                .stream()
                .map(AiSessionResponse::from)
                .toList();
    }

    private Long checkedProject(String owner, Long projectId) {
        if (projectId == null) return null;
        var project = projectService.requireOwned(projectId, owner);
        if (project.getStatus() == AiProjectStatus.ARCHIVED) {
            throw new AiServiceException(
                    HttpStatus.CONFLICT, "Archived AI project cannot contain new sessions");
        }
        return project.getId();
    }
}
