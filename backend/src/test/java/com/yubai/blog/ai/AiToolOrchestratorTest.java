package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiImageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiToolOrchestratorTest {
    @Mock private AiTaskService taskService;
    @Mock private AiArtifactService artifactService;
    @Mock private AiArtifactRepository artifactRepository;
    @Mock private AiTaskPartRepository partRepository;
    @Mock private AiImageService imageService;
    @Mock private AiActionProposalService proposalService;

    private AiToolOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator =
                new AiToolOrchestrator(
                        new ObjectMapper(),
                        taskService,
                        artifactService,
                        artifactRepository,
                        partRepository,
                        imageService,
                        proposalService);
    }

    @Test
    void proposalToolKeepsStructuredArgumentsAndReturnsCandidateOnly() {
        var taskId = UUID.randomUUID();
        var proposalId = UUID.randomUUID();
        when(partRepository.findByTaskIdOrderBySequenceAsc(taskId)).thenReturn(List.of());
        var response = mock(AiActionProposalService.Response.class);
        when(response.id()).thenReturn(proposalId);
        when(response.actionType()).thenReturn("update_post");
        var created = mock(AiActionProposalService.CreatedProposal.class);
        when(created.proposal()).thenReturn(response);
        when(proposalService.create(eq("alice"), any())).thenReturn(created);

        var batch =
                orchestrator.execute(
                        taskId,
                        "alice",
                        List.of(
                                new AiToolCall(
                                        "call-1",
                                        "propose_action",
                                        "{\"actionType\":\"update_post\",\"targetType\":\"post\",\"arguments\":{\"title\":\"Draft\"}}")));

        assertThat(batch.failures()).isEmpty();
        assertThat(batch.results())
                .singleElement()
                .satisfies(
                        result -> {
                            assertThat(result.artifactId()).isNull();
                            assertThat(result.name()).isEqualTo("proposal:" + proposalId);
                            assertThat(result.payload()).contains("PROPOSED");
                        });
        var request = ArgumentCaptor.forClass(AiActionProposalService.CreateRequest.class);
        verify(proposalService).create(eq("alice"), request.capture());
        assertThat(request.getValue().arguments()).isEqualTo("{\"title\":\"Draft\"}");
        verify(artifactService, never()).create(any(), any(), any());
    }

    @Test
    void highRiskToolNamesAreRejectedBeforeProposalCreation() {
        var taskId = UUID.randomUUID();
        when(partRepository.findByTaskIdOrderBySequenceAsc(taskId)).thenReturn(List.of());

        var batch =
                orchestrator.execute(
                        taskId, "alice", List.of(new AiToolCall("call-2", "publish_post", "{}")));

        assertThat(batch.results()).isEmpty();
        assertThat(batch.failures())
                .singleElement()
                .satisfies(failure -> assertThat(failure).contains("never AI tools"));
        verify(proposalService, never()).create(any(), any());
    }
}
