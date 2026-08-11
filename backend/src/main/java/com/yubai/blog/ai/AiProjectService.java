package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiProjectService {
    private final AiProjectRepository projectRepository;
    private final AiSessionRepository sessionRepository;

    public AiProjectService(
            AiProjectRepository projectRepository, AiSessionRepository sessionRepository) {
        this.projectRepository = projectRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public AiProjectResponse create(String owner, AiProjectCreateRequest request) {
        var sortOrder = projectRepository.countByOwnerAndStatus(owner, AiProjectStatus.ACTIVE);
        var project =
                projectRepository.save(AiProjectEntity.create(owner, request.title(), sortOrder));
        return toResponse(project);
    }

    @Transactional(readOnly = true)
    public List<AiProjectResponse> list(String owner) {
        return projectRepository
                .findByOwnerOrderByStatusAscSortOrderAscUpdatedAtDesc(owner)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AiProjectEntity requireOwned(Long id, String owner) {
        return projectRepository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI project does not exist"));
    }

    @Transactional
    public AiProjectResponse rename(Long id, String owner, AiProjectUpdateRequest request) {
        var project = requireOwned(id, owner);
        if (project.getVersion() != request.version()) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI project version conflict");
        }
        try {
            project.rename(request.title());
        } catch (IllegalStateException exception) {
            throw new AiServiceException(HttpStatus.CONFLICT, exception.getMessage());
        } catch (IllegalArgumentException exception) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, exception.getMessage());
        }
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public AiProjectResponse archive(Long id, String owner) {
        var project = requireOwned(id, owner);
        project.archive();
        return toResponse(projectRepository.save(project));
    }

    @Transactional
    public AiProjectResponse restore(Long id, String owner) {
        var project = requireOwned(id, owner);
        project.restore();
        return toResponse(projectRepository.save(project));
    }

    private AiProjectResponse toResponse(AiProjectEntity project) {
        return AiProjectResponse.from(
                project,
                sessionRepository.countByOwnerAndProjectIdAndStatusNot(
                        project.getOwner(), project.getId(), AiSessionStatus.DELETED));
    }
}
