package com.yubai.blog.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AiActionProposalService {
    private static final Duration DEFAULT_TTL = Duration.ofMinutes(30);
    private static final Duration MAX_TTL = Duration.ofHours(2);
    private final AiActionProposalRepository repository;
    private final AiTaskService taskService;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final com.yubai.blog.graph.GraphRelationService graphRelationService;
    private final SecureRandom random = new SecureRandom();

    public AiActionProposalService(
            AiActionProposalRepository repository,
            AiTaskService taskService,
            ObjectMapper objectMapper,
            Clock clock) {
        this(repository, taskService, objectMapper, clock, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AiActionProposalService(
            AiActionProposalRepository repository,
            AiTaskService taskService,
            ObjectMapper objectMapper,
            Clock clock,
            com.yubai.blog.graph.GraphRelationService graphRelationService) {
        this.repository = repository;
        this.taskService = taskService;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.graphRelationService = graphRelationService;
    }

    @Transactional
    public CreatedProposal create(String owner, CreateRequest request) {
        if (request.taskId() != null) taskService.requireOwned(request.taskId(), owner);
        var actionType = normalizeActionType(request.actionType());
        var arguments = canonicalObject(request.arguments());
        var rawNonce = randomToken();
        var ttl =
                request.ttlMinutes() == null
                        ? DEFAULT_TTL
                        : Duration.ofMinutes(request.ttlMinutes());
        if (ttl.isZero() || ttl.isNegative() || ttl.compareTo(MAX_TTL) > 0) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST, "proposal TTL must be between 1 and 120 minutes");
        }
        var entity =
                repository.save(
                        AiActionProposalEntity.create(
                                owner,
                                request.taskId(),
                                actionType,
                                normalize(request.targetType()),
                                normalize(request.targetId()),
                                request.targetVersion(),
                                arguments,
                                sha256(arguments),
                                sha256(rawNonce),
                                clock.instant().plus(ttl)));
        return new CreatedProposal(toResponse(entity, rawNonce));
    }

    public List<Response> list(String owner, AiActionProposalStatus status) {
        var proposals =
                status == null
                        ? repository.findByOwnerOrderByCreatedAtDesc(owner)
                        : repository.findByOwnerAndStatusOrderByCreatedAtDesc(owner, status);
        return proposals.stream().map(entity -> toResponse(entity, null)).toList();
    }

    @Transactional(noRollbackFor = AiServiceException.class)
    public Response approve(UUID id, String owner, String nonce, Long expectedTargetVersion) {
        var entity = owned(id, owner);
        ensureProposed(entity);
        if (entity.getTargetVersion() != null
                && !java.util.Objects.equals(entity.getTargetVersion(), expectedTargetVersion)) {
            throw new AiServiceException(HttpStatus.CONFLICT, "proposal target version is stale");
        }
        requireNonce(entity, nonce);
        executeApprovedGraphRelation(entity, owner);
        entity.approve(owner, clock.instant());
        return toResponse(entity, null);
    }

    private void executeApprovedGraphRelation(AiActionProposalEntity entity, String owner) {
        if (!"graph.relation.create".equals(entity.getActionType())) return;
        if (graphRelationService == null) {
            throw new AiServiceException(
                    HttpStatus.SERVICE_UNAVAILABLE, "graph relation approval is unavailable");
        }
        try {
            var arguments = objectMapper.readTree(entity.getArguments());
            graphRelationService.create(
                    new com.yubai.blog.graph.GraphRelationService.CreateRequest(
                            arguments.path("sourceId").asText(),
                            arguments.path("targetId").asText(),
                            arguments.path("relationType").asText()),
                    owner,
                    com.yubai.blog.graph.GraphRelationOrigin.AI_APPROVED);
        } catch (com.yubai.blog.admin.ai.AiServiceException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AiServiceException(HttpStatus.CONFLICT, "graph relation proposal is invalid");
        }
    }

    @Transactional
    public Response reject(UUID id, String owner, String nonce, String reason) {
        var entity = owned(id, owner);
        ensureProposed(entity);
        requireNonce(entity, nonce);
        entity.reject(owner, normalize(reason), clock.instant());
        return toResponse(entity, null);
    }

    public Response get(UUID id, String owner) {
        return toResponse(owned(id, owner), null);
    }

    private void ensureProposed(AiActionProposalEntity entity) {
        if (entity.getStatus() != AiActionProposalStatus.PROPOSED) {
            throw new AiServiceException(HttpStatus.CONFLICT, "proposal is no longer pending");
        }
        if (!entity.getExpiresAt().isAfter(clock.instant())) {
            entity.expire();
            throw new AiServiceException(HttpStatus.GONE, "proposal has expired");
        }
    }

    private void requireNonce(AiActionProposalEntity entity, String nonce) {
        if (nonce == null
                || !MessageDigest.isEqual(
                        hexBytes(sha256(nonce)), hexBytes(entity.getNonceHash()))) {
            throw new AiServiceException(HttpStatus.UNAUTHORIZED, "proposal nonce is invalid");
        }
    }

    private AiActionProposalEntity owned(UUID id, String owner) {
        return repository
                .findByIdAndOwner(id, owner)
                .orElseThrow(() -> new NotFoundException("AI proposal does not exist"));
    }

    private Response toResponse(AiActionProposalEntity entity, String nonce) {
        return new Response(
                entity.getId(),
                entity.getTaskId(),
                entity.getActionType(),
                entity.getTargetType(),
                entity.getTargetId(),
                entity.getTargetVersion(),
                entity.getArguments(),
                entity.getStatus(),
                entity.getExpiresAt(),
                entity.getVersion(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getApprovedAt(),
                entity.getApprovedBy(),
                entity.getRejectedAt(),
                entity.getRejectedBy(),
                entity.getRejectedReason(),
                nonce);
    }

    private String canonicalObject(String raw) {
        try {
            var node = objectMapper.readTree(raw == null || raw.isBlank() ? "{}" : raw);
            if (node == null || !node.isObject()) throw new IllegalArgumentException();
            return objectMapper.writeValueAsString(node);
        } catch (Exception exception) {
            throw new AiServiceException(
                    HttpStatus.BAD_REQUEST, "proposal arguments must be a JSON object");
        }
    }

    private static String normalizeActionType(String value) {
        var normalized = normalize(value);
        if (normalized == null || !normalized.matches("[a-z][a-z0-9_.-]{0,79}")) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST, "proposal action type is invalid");
        }
        if (normalized.contains("publish")
                || normalized.contains("delete")
                || normalized.contains("schedule")) {
            throw new AiServiceException(
                    HttpStatus.FORBIDDEN,
                    "AI proposals cannot publish, delete, or schedule content");
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String randomToken() {
        var bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] hexBytes(String value) {
        return HexFormat.of().parseHex(value);
    }

    public record CreateRequest(
            UUID taskId,
            String actionType,
            String targetType,
            String targetId,
            Long targetVersion,
            String arguments,
            Integer ttlMinutes) {}

    public record CreatedProposal(Response proposal) {}

    public record Response(
            UUID id,
            UUID taskId,
            String actionType,
            String targetType,
            String targetId,
            Long targetVersion,
            String arguments,
            AiActionProposalStatus status,
            java.time.Instant expiresAt,
            long version,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            java.time.Instant approvedAt,
            String approvedBy,
            java.time.Instant rejectedAt,
            String rejectedBy,
            String rejectedReason,
            String nonce) {}
}
