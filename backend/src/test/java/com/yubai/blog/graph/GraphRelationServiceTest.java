package com.yubai.blog.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GraphRelationServiceTest {
    @Mock private GraphRelationRepository relationRepository;
    @Mock private GraphRelationAuditRepository auditRepository;
    @Mock private GraphService graphService;

    private GraphRelationService service;
    private GraphResponse graph;

    @BeforeEach
    void setUp() {
        service =
                new GraphRelationService(
                        relationRepository, auditRepository, graphService, new ObjectMapper());
        graph =
                new GraphResponse(
                        List.of(
                                new GraphNode("p-1", "One", "POST", "/articles/one"),
                                new GraphNode("p-2", "Two", "POST", "/articles/two")),
                        List.of());
        lenient().when(graphService.buildGraph(true)).thenReturn(graph);
    }

    @Test
    void createsAuditedRelationAfterValidatingBothNodes() {
        when(relationRepository.findBySourceIdAndTargetIdAndRelationType(
                        "p-1", "p-2", "related_to"))
                .thenReturn(Optional.empty());
        when(relationRepository.save(any(GraphRelationEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        var result =
                service.create(
                        new GraphRelationService.CreateRequest("p-1", "p-2", "related_to"),
                        "alice",
                        GraphRelationOrigin.MANUAL);

        assertThat(result.sourceId()).isEqualTo("p-1");
        assertThat(result.targetId()).isEqualTo("p-2");
        assertThat(result.origin()).isEqualTo(GraphRelationOrigin.MANUAL);
        verify(auditRepository).save(any(GraphRelationAuditEntity.class));
    }

    @Test
    void rejectsSelfLoopDuplicateAndOrphan() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new GraphRelationService.CreateRequest(
                                                "p-1", "p-1", "related_to"),
                                        "alice",
                                        GraphRelationOrigin.MANUAL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("self");

        when(relationRepository.findBySourceIdAndTargetIdAndRelationType(
                        "p-1", "p-2", "related_to"))
                .thenReturn(
                        Optional.of(
                                GraphRelationEntity.create(
                                        "p-1",
                                        "p-2",
                                        "related_to",
                                        GraphRelationOrigin.MANUAL,
                                        "alice")));
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new GraphRelationService.CreateRequest(
                                                "p-1", "p-2", "related_to"),
                                        "alice",
                                        GraphRelationOrigin.MANUAL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists");

        when(graphService.buildGraph(true))
                .thenReturn(
                        new GraphResponse(
                                List.of(new GraphNode("p-1", "One", "POST", "/articles/one")),
                                List.of()));
        assertThatThrownBy(
                        () ->
                                service.create(
                                        new GraphRelationService.CreateRequest(
                                                "p-1", "missing", "related_to"),
                                        "alice",
                                        GraphRelationOrigin.MANUAL))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not exist");
    }

    @Test
    void rejectsStaleUpdateAndFiltersOrphanedExplicitEdges() {
        var relation =
                GraphRelationEntity.create(
                        "p-1", "p-2", "related_to", GraphRelationOrigin.MANUAL, "alice");
        when(relationRepository.findById(any(UUID.class))).thenReturn(Optional.of(relation));
        assertThatThrownBy(
                        () ->
                                service.update(
                                        relation.getId(),
                                        1,
                                        new GraphRelationService.UpdateRequest(
                                                "p-1", "p-2", "related_to"),
                                        "alice"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("version conflict");

        var orphan =
                GraphRelationEntity.create(
                        "p-1", "missing", "related_to", GraphRelationOrigin.MANUAL, "alice");
        when(relationRepository.findAllByOrderByCreatedAtAscIdAsc())
                .thenReturn(List.of(relation, orphan));
        var merged = service.mergeWithDerivedGraph(true);
        assertThat(merged.edges()).containsExactly(new GraphEdge("p-1", "p-2"));
    }

    @Test
    void importPreviewReportsDuplicateItemsAndOrphansWithoutWriting() {
        when(relationRepository.findAll()).thenReturn(List.of());
        var preview =
                service.previewImport(
                        "{\"relations\":["
                                + "{\"sourceId\":\"p-1\",\"targetId\":\"p-2\",\"relationType\":\"related_to\"},"
                                + "{\"sourceId\":\"p-1\",\"targetId\":\"p-2\",\"relationType\":\"related_to\"},"
                                + "{\"sourceId\":\"p-1\",\"targetId\":\"missing\",\"relationType\":\"related_to\"}]}",
                        true);

        assertThat(preview.schemaVersion()).isEqualTo("2.0");
        assertThat(preview.acceptedCount()).isEqualTo(1);
        assertThat(preview.conflictCount()).isEqualTo(2);
        assertThat(preview.conflicts()).anyMatch(item -> item.contains("duplicate"));
    }

    @Test
    void deleteWritesAnAuditRowBeforeRemovingRelation() {
        var relation =
                GraphRelationEntity.create(
                        "p-1", "p-2", "related_to", GraphRelationOrigin.MANUAL, "alice");
        when(relationRepository.findById(relation.getId())).thenReturn(Optional.of(relation));

        service.delete(relation.getId(), 0, "alice");

        verify(auditRepository).save(any(GraphRelationAuditEntity.class));
        verify(relationRepository).delete(relation);
    }

    @Test
    void sourceAndTargetFiltersRemainIndependentForBacklinks() {
        when(relationRepository.findByTargetIdOrderByCreatedAtAscIdAsc("p-2"))
                .thenReturn(
                        List.of(
                                GraphRelationEntity.create(
                                        "p-1",
                                        "p-2",
                                        "related_to",
                                        GraphRelationOrigin.MANUAL,
                                        "alice")));

        assertThat(service.list(null, "p-2")).hasSize(1);
        verify(relationRepository).findByTargetIdOrderByCreatedAtAscIdAsc("p-2");
    }
}
