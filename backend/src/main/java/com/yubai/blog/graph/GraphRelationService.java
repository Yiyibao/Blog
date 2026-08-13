package com.yubai.blog.graph;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.common.NotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class GraphRelationService {
    private static final int MAX_RELATIONS = 10_000;
    private static final Comparator<GraphRelationEntity> RELATION_ORDER =
            Comparator.comparing(GraphRelationEntity::getSourceId)
                    .thenComparing(GraphRelationEntity::getTargetId)
                    .thenComparing(GraphRelationEntity::getRelationType)
                    .thenComparing(GraphRelationEntity::getId);

    private final GraphRelationRepository relationRepository;
    private final GraphRelationAuditRepository auditRepository;
    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    public GraphRelationService(
            GraphRelationRepository relationRepository,
            GraphRelationAuditRepository auditRepository,
            GraphService graphService,
            ObjectMapper objectMapper) {
        this.relationRepository = relationRepository;
        this.auditRepository = auditRepository;
        this.graphService = graphService;
        this.objectMapper = objectMapper;
    }

    public List<Response> list(String sourceId, String targetId) {
        sourceId = blankToNull(sourceId);
        targetId = blankToNull(targetId);
        var relations =
                sourceId == null && targetId == null
                        ? relationRepository.findAllByOrderByCreatedAtAscIdAsc()
                        : sourceId != null && targetId != null
                                ? relationRepository
                                        .findBySourceIdOrTargetIdOrderByCreatedAtAscIdAsc(
                                                normalizeId(sourceId), normalizeId(targetId))
                                : sourceId != null
                                        ? relationRepository.findBySourceIdOrderByCreatedAtAscIdAsc(
                                                normalizeId(sourceId))
                                        : relationRepository.findByTargetIdOrderByCreatedAtAscIdAsc(
                                                normalizeId(targetId));
        return relations.stream()
                .sorted(RELATION_ORDER)
                .map(GraphRelationService::toResponse)
                .toList();
    }

    public List<AuditResponse> audits(UUID relationId) {
        return auditRepository.findByRelationIdOrderByCreatedAtDesc(relationId).stream()
                .map(
                        audit ->
                                new AuditResponse(
                                        audit.getId(),
                                        audit.getRelationId(),
                                        audit.getSourceId(),
                                        audit.getTargetId(),
                                        audit.getRelationType(),
                                        audit.getOrigin(),
                                        audit.getAction(),
                                        audit.getActor(),
                                        audit.getRelationVersion(),
                                        audit.getCreatedAt()))
                .toList();
    }

    @Transactional
    @CacheEvict(cacheNames = "graph", allEntries = true)
    public Response create(CreateRequest request, String actor, GraphRelationOrigin origin) {
        var sourceId = normalizeId(request.sourceId());
        var targetId = normalizeId(request.targetId());
        var type = normalizeType(request.relationType());
        ensureNodeExists(sourceId, actor);
        ensureNodeExists(targetId, actor);
        if (sourceId.equals(targetId)) throw conflict("graph relation cannot point to itself");
        if (relationRepository
                .findBySourceIdAndTargetIdAndRelationType(sourceId, targetId, type)
                .isPresent()) {
            throw conflict("graph relation already exists");
        }
        var relation =
                relationRepository.save(
                        GraphRelationEntity.create(sourceId, targetId, type, origin, actor));
        auditRepository.save(
                GraphRelationAuditEntity.record(
                        relation, GraphRelationAction.CREATE, actor, relation.getVersion()));
        return toResponse(relation);
    }

    @Transactional
    @CacheEvict(cacheNames = "graph", allEntries = true)
    public Response update(UUID id, long expectedVersion, UpdateRequest request, String actor) {
        var relation = relation(id);
        if (relation.getVersion() != expectedVersion)
            throw conflict("graph relation version conflict");
        var sourceId = normalizeId(request.sourceId());
        var targetId = normalizeId(request.targetId());
        var type = normalizeType(request.relationType());
        ensureNodeExists(sourceId, actor);
        ensureNodeExists(targetId, actor);
        if (sourceId.equals(targetId)) throw conflict("graph relation cannot point to itself");
        relationRepository
                .findBySourceIdAndTargetIdAndRelationType(sourceId, targetId, type)
                .filter(existing -> !existing.getId().equals(id))
                .ifPresent(
                        existing -> {
                            throw conflict("graph relation already exists");
                        });
        relation.update(sourceId, targetId, type);
        var saved = relationRepository.saveAndFlush(relation);
        auditRepository.save(
                GraphRelationAuditEntity.record(
                        saved, GraphRelationAction.UPDATE, actor, saved.getVersion()));
        return toResponse(saved);
    }

    @Transactional
    @CacheEvict(cacheNames = "graph", allEntries = true)
    public void delete(UUID id, long expectedVersion, String actor) {
        var relation = relation(id);
        if (relation.getVersion() != expectedVersion)
            throw conflict("graph relation version conflict");
        auditRepository.save(
                GraphRelationAuditEntity.recordDeleted(
                        relation.getId(),
                        relation.getSourceId(),
                        relation.getTargetId(),
                        relation.getRelationType(),
                        relation.getOrigin(),
                        actor,
                        relation.getVersion()));
        relationRepository.delete(relation);
    }

    public GraphResponse mergeWithDerivedGraph(boolean includeNotes) {
        var derived = graphService.buildGraph(includeNotes);
        var relations = relationRepository.findAllByOrderByCreatedAtAscIdAsc();
        var validNodeIds =
                derived.nodes().stream()
                        .map(GraphNode::id)
                        .collect(java.util.stream.Collectors.toSet());
        var edges = new ArrayList<>(derived.edges());
        relations.stream()
                .filter(relation -> validNodeIds.contains(relation.getSourceId()))
                .filter(relation -> validNodeIds.contains(relation.getTargetId()))
                .map(relation -> new GraphEdge(relation.getSourceId(), relation.getTargetId()))
                .forEach(
                        edge -> {
                            if (!edges.contains(edge)) edges.add(edge);
                        });
        edges.sort(Comparator.comparing(GraphEdge::source).thenComparing(GraphEdge::target));
        return new GraphResponse(derived.nodes(), List.copyOf(edges));
    }

    public ImportPreview previewImport(String raw, boolean includeNotes) {
        var requested = parseImport(raw);
        var graph = mergeWithDerivedGraph(includeNotes);
        var nodeIds =
                graph.nodes().stream()
                        .map(GraphNode::id)
                        .collect(java.util.stream.Collectors.toSet());
        var existing = new java.util.HashSet<String>();
        relationRepository
                .findAll()
                .forEach(
                        r ->
                                existing.add(
                                        identity(
                                                r.getSourceId(),
                                                r.getTargetId(),
                                                r.getRelationType())));
        var conflicts = new ArrayList<String>();
        var accepted = new ArrayList<ImportItem>();
        for (var item : requested) {
            var identity = identity(item.sourceId(), item.targetId(), item.relationType());
            if (item.sourceId().equals(item.targetId())
                    || !nodeIds.contains(item.sourceId())
                    || !nodeIds.contains(item.targetId())) {
                conflicts.add(identity + ": orphan or self-loop");
            } else if (existing.contains(identity)) {
                conflicts.add(identity + ": duplicate");
            } else {
                accepted.add(item);
                existing.add(identity);
            }
        }
        return new ImportPreview(
                "2.0",
                accepted.size(),
                conflicts.size(),
                List.copyOf(accepted),
                List.copyOf(conflicts));
    }

    private void ensureNodeExists(String nodeId, String actor) {
        var graph = graphService.buildGraph(true);
        if (graph.nodes().stream().noneMatch(node -> node.id().equals(nodeId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "graph node does not exist");
        }
    }

    private GraphRelationEntity relation(UUID id) {
        return relationRepository
                .findById(id)
                .orElseThrow(() -> new NotFoundException("graph relation does not exist"));
    }

    private List<ImportItem> parseImport(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw == null ? "{}" : raw);
            var items = root == null ? null : root.get("relations");
            if (items == null || !items.isArray() || items.size() > MAX_RELATIONS)
                throw new IllegalArgumentException();
            var result = new ArrayList<ImportItem>();
            for (var item : items) {
                result.add(
                        new ImportItem(
                                normalizeId(required(item, "sourceId")),
                                normalizeId(required(item, "targetId")),
                                normalizeType(required(item, "relationType"))));
            }
            return result;
        } catch (Exception exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "graph import must contain a valid relations array");
        }
    }

    private static String required(JsonNode item, String field) {
        var value = item == null ? null : item.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException();
        return value.asText();
    }

    private static String normalizeId(String value) {
        if (value == null || value.isBlank() || value.trim().length() > 128)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "graph node id is invalid");
        return value.trim();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeType(String value) {
        var normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z][a-z0-9_.-]{0,63}"))
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "graph relation type is invalid");
        return normalized;
    }

    private static String identity(String source, String target, String type) {
        return source + "|" + target + "|" + type;
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static Response toResponse(GraphRelationEntity relation) {
        return new Response(
                relation.getId(),
                relation.getSourceId(),
                relation.getTargetId(),
                relation.getRelationType(),
                relation.getOrigin(),
                relation.getCreatedBy(),
                relation.getCreatedAt(),
                relation.getUpdatedAt(),
                relation.getVersion());
    }

    public record CreateRequest(String sourceId, String targetId, String relationType) {}

    public record UpdateRequest(String sourceId, String targetId, String relationType) {}

    public record Response(
            UUID id,
            String sourceId,
            String targetId,
            String relationType,
            GraphRelationOrigin origin,
            String createdBy,
            java.time.Instant createdAt,
            java.time.Instant updatedAt,
            long version) {}

    public record AuditResponse(
            UUID id,
            UUID relationId,
            String sourceId,
            String targetId,
            String relationType,
            GraphRelationOrigin origin,
            GraphRelationAction action,
            String actor,
            long relationVersion,
            java.time.Instant createdAt) {}

    public record ImportItem(String sourceId, String targetId, String relationType) {}

    public record ImportPreview(
            String schemaVersion,
            int acceptedCount,
            int conflictCount,
            List<ImportItem> accepted,
            List<String> conflicts) {}
}
