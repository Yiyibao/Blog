package com.yubai.blog.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yubai.blog.admin.ai.AiServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiActionProposalServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-13T08:00:00Z");

    @Mock private AiActionProposalRepository repository;
    @Mock private AiTaskService taskService;

    private AiActionProposalService service;

    @BeforeEach
    void setUp() {
        service =
                new AiActionProposalService(
                        repository,
                        taskService,
                        new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsHashOnlyNonceAndApprovalDoesNotExecuteAnything() {
        var saved = new AtomicReference<AiActionProposalEntity>();
        when(repository.save(any()))
                .thenAnswer(
                        invocation -> {
                            var entity = invocation.getArgument(0, AiActionProposalEntity.class);
                            saved.set(entity);
                            return entity;
                        });

        var created =
                service.create(
                        "alice",
                        new AiActionProposalService.CreateRequest(
                                null,
                                "update_post",
                                "post",
                                "42",
                                7L,
                                "{\"title\":\"Draft\",\"body\":\"Safe\"}",
                                15));

        var response = created.proposal();
        var entity = saved.get();
        assertThat(response.status()).isEqualTo(AiActionProposalStatus.PROPOSED);
        assertThat(response.nonce()).isNotBlank();
        assertThat(entity.getNonceHash()).isNotEqualTo(response.nonce());
        assertThat(entity.getNonceHash()).hasSize(64);
        assertThat(response.arguments()).contains("\"title\":\"Draft\"");

        when(repository.findByIdAndOwner(response.id(), "alice")).thenReturn(Optional.of(entity));
        var approved = service.approve(response.id(), "alice", response.nonce(), 7L);

        assertThat(approved.status()).isEqualTo(AiActionProposalStatus.APPROVED);
        assertThat(approved.nonce()).isNull();
        assertThat(entity.getStatus()).isEqualTo(AiActionProposalStatus.APPROVED);
    }

    @Test
    void rejectsWrongNonceAndStaleTargetVersion() {
        var entity =
                AiActionProposalEntity.create(
                        "alice",
                        null,
                        "update_post",
                        "post",
                        "42",
                        7L,
                        "{}",
                        "arguments-hash",
                        "00".repeat(32),
                        NOW.plusSeconds(900));
        var proposalId = entity.getId();
        when(repository.findByIdAndOwner(proposalId, "alice")).thenReturn(Optional.of(entity));

        assertThatThrownBy(() -> service.approve(proposalId, "alice", "wrong", 7L))
                .isInstanceOf(AiServiceException.class);
        assertThat(entity.getStatus()).isEqualTo(AiActionProposalStatus.PROPOSED);

        assertThatThrownBy(() -> service.approve(proposalId, "alice", "wrong", 8L))
                .isInstanceOf(AiServiceException.class);
    }

    @Test
    void refusesHighRiskActionTypesAndNonObjectArguments() {
        assertThatThrownBy(
                        () ->
                                service.create(
                                        "alice",
                                        new AiActionProposalService.CreateRequest(
                                                null,
                                                "publish_post",
                                                null,
                                                null,
                                                null,
                                                "{}",
                                                null)))
                .isInstanceOf(AiServiceException.class);

        assertThatThrownBy(
                        () ->
                                service.create(
                                        "alice",
                                        new AiActionProposalService.CreateRequest(
                                                null, "update_post", null, null, null, "[]", null)))
                .isInstanceOf(AiServiceException.class);
    }
}
