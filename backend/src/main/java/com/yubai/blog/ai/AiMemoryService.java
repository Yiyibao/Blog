package com.yubai.blog.ai;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiMemoryService {
    private static final Pattern SENSITIVE =
            Pattern.compile(
                    "(?i)(password|passwd|密码|token|api[ _-]?key|secret|身份证|病历|health record)");
    private final AiMemoryRepository repository;
    private final AiTaskRepository taskRepository;
    private final AiSessionRepository sessionRepository;

    public AiMemoryService(
            AiMemoryRepository repository,
            AiTaskRepository taskRepository,
            AiSessionRepository sessionRepository) {
        this.repository = repository;
        this.taskRepository = taskRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public AiMemoryResponse create(String owner, AiMemoryCreateRequest request) {
        var content = normalizeContent(request.content());
        rejectSensitive(content);
        if (request.sourceTaskId() != null
                && taskRepository.findByIdAndOwner(request.sourceTaskId(), owner).isEmpty()) {
            throw new NotFoundException("AI source task does not exist");
        }
        var status =
                request.sourceTaskId() == null ? AiMemoryStatus.ACTIVE : AiMemoryStatus.PROPOSED;
        var entity =
                AiMemoryEntity.create(
                        owner,
                        normalizeScope(owner, request.scope()),
                        request.kind().trim().toUpperCase(Locale.ROOT),
                        content,
                        request.sourceTaskId(),
                        request.sourceRef(),
                        status,
                        request.confidence(),
                        request.expiresAt());
        return AiMemoryResponse.from(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<AiMemoryResponse> list(String owner) {
        return repository
                .findByOwnerAndStatusNotOrderByUpdatedAtDesc(owner, AiMemoryStatus.DELETED)
                .stream()
                .map(AiMemoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AiMemoryResponse get(UUID id, String owner) {
        return AiMemoryResponse.from(requireOwned(id, owner));
    }

    @Transactional
    public AiMemoryResponse confirm(UUID id, String owner) {
        var memory = requireOwned(id, owner);
        try {
            memory.confirm();
        } catch (IllegalStateException exception) {
            throw new AiServiceException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return AiMemoryResponse.from(repository.save(memory));
    }

    @Transactional
    public AiMemoryResponse update(UUID id, String owner, AiMemoryUpdateRequest request) {
        var memory = requireOwned(id, owner);
        if (memory.getVersion() != request.version()) {
            throw new AiServiceException(HttpStatus.CONFLICT, "AI memory version conflict");
        }
        var content = normalizeContent(request.content());
        rejectSensitive(content);
        try {
            memory.update(
                    normalizeScope(owner, request.scope()),
                    request.kind().trim().toUpperCase(Locale.ROOT),
                    content,
                    request.expiresAt());
        } catch (IllegalStateException exception) {
            throw new AiServiceException(HttpStatus.CONFLICT, exception.getMessage());
        }
        return AiMemoryResponse.from(repository.save(memory));
    }

    @Transactional
    public AiMemoryResponse disable(UUID id, String owner) {
        var memory = requireOwned(id, owner);
        memory.disable();
        return AiMemoryResponse.from(repository.save(memory));
    }

    @Transactional
    public AiMemoryResponse enable(UUID id, String owner) {
        var memory = requireOwned(id, owner);
        memory.enable();
        return AiMemoryResponse.from(repository.save(memory));
    }

    @Transactional
    public AiMemoryResponse reject(UUID id, String owner) {
        var memory = requireOwned(id, owner);
        memory.reject();
        return AiMemoryResponse.from(repository.save(memory));
    }

    @Transactional
    public void forget(UUID id, String owner) {
        var memory = requireOwned(id, owner);
        memory.forget();
        repository.save(memory);
        sessionRepository
                .findByOwnerOrderByUpdatedAtDesc(owner)
                .forEach(
                        session -> {
                            if (session.getSummary() != null) {
                                session.updateSummary(null);
                                sessionRepository.save(session);
                            }
                        });
    }

    @Transactional(readOnly = true)
    public List<AiMemoryEntity> activeForContext(String owner) {
        return repository.findActiveForContext(
                owner, AiMemoryStatus.ACTIVE, Instant.now(), PageRequest.of(0, 200));
    }

    @Transactional(readOnly = true)
    public List<AiMemoryEntity> activeForContext(String owner, Long sessionId) {
        var sessionScope = "SESSION:" + sessionId;
        return activeForContext(owner).stream()
                .filter(
                        memory ->
                                switch (memory.getScope()) {
                                    case "USER", "GLOBAL", "SITE" -> true;
                                    default -> sessionScope.equals(memory.getScope());
                                })
                .toList();
    }

    private AiMemoryEntity requireOwned(UUID id, String owner) {
        return repository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI memory does not exist"));
    }

    private static String normalizeContent(String content) {
        return content.replaceAll("\\s+", " ").trim();
    }

    private String normalizeScope(String owner, String scope) {
        var normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (normalized.equals("USER") || normalized.equals("GLOBAL") || normalized.equals("SITE")) {
            return normalized;
        }
        if (normalized.startsWith("SESSION:")) {
            try {
                var sessionId = Long.parseLong(normalized.substring("SESSION:".length()));
                if (sessionRepository.findByIdAndOwner(sessionId, owner).isPresent()) {
                    return "SESSION:" + sessionId;
                }
            } catch (NumberFormatException ignored) {
                // Report one stable validation error below.
            }
        }
        throw new AiServiceException(HttpStatus.BAD_REQUEST, "Unsupported AI memory scope");
    }

    private static void rejectSensitive(String content) {
        if (SENSITIVE.matcher(content).find()) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST,
                    "Sensitive credentials, identity or health data cannot be stored as memory");
        }
    }
}
