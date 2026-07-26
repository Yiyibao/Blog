package com.yubai.blog.admin.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 4A-6：日预算闸门与用量审计（不存消息内容、env 回退跳过、审计失败不冒泡）。 */
class AiUsageServiceTest {

    private AiUsageRepository repository;
    private AiUsageService service;

    private static AiUsageRepository.UsageAggregate aggregate(long requests, long tokens) {
        return new AiUsageRepository.UsageAggregate() {
            @Override public long getRequests() { return requests; }
            @Override public long getTokens() { return tokens; }
        };
    }

    private static AiEndpoint registryEndpoint(int requestLimit, int tokenLimit) {
        return new AiEndpoint(7L, "https://api.example.com", "key", "deepseek-v4-flash", 60, 2048,
            requestLimit, tokenLimit);
    }

    @BeforeEach
    void setUp() {
        repository = mock(AiUsageRepository.class);
        service = new AiUsageService(repository);
    }

    @Test
    void withinBudgetPasses() {
        when(repository.aggregateSince(anyLong(), any())).thenReturn(aggregate(5, 1000));
        assertThatCode(() -> service.assertWithinDailyBudget(registryEndpoint(10, 10_000)))
            .doesNotThrowAnyException();
    }

    @Test
    void requestBudgetExceededThrows429() {
        when(repository.aggregateSince(anyLong(), any())).thenReturn(aggregate(10, 1000));
        assertThatThrownBy(() -> service.assertWithinDailyBudget(registryEndpoint(10, 0)))
            .isInstanceOf(AiServiceException.class)
            .satisfies(e -> assertThat(((AiServiceException) e).getStatus().value()).isEqualTo(429));
    }

    @Test
    void tokenBudgetExceededThrows429() {
        when(repository.aggregateSince(anyLong(), any())).thenReturn(aggregate(1, 200_000));
        assertThatThrownBy(() -> service.assertWithinDailyBudget(registryEndpoint(0, 200_000)))
            .isInstanceOf(AiServiceException.class);
    }

    @Test
    void envFallbackEndpointSkipsBudgetAndAudit() {
        var envEndpoint = new AiEndpoint("https://api.deepseek.com", "key", "m", 60, 2048);
        assertThatCode(() -> service.assertWithinDailyBudget(envEndpoint)).doesNotThrowAnyException();
        service.recordSuccess(envEndpoint, new ChatResponse("hi", "m", null), 100);
        verify(repository, never()).save(any());
    }

    @Test
    void successAuditRowCarriesTokensAndNeverMessageContent() {
        var captor = ArgumentCaptor.forClass(AiUsageEntity.class);
        service.recordSuccess(registryEndpoint(0, 0),
            new ChatResponse("敏感回复内容", "deepseek-v4-flash", new ChatResponse.Usage(120, 80, 200)), 543);
        verify(repository).save(captor.capture());
        var row = captor.getValue();
        assertThat(row.getProviderId()).isEqualTo(7L);
        assertThat(row.getPromptTokens()).isEqualTo(120);
        assertThat(row.getCompletionTokens()).isEqualTo(80);
        assertThat(row.getLatencyMs()).isEqualTo(543);
        assertThat(row.getStatus()).isEqualTo("OK");
        // 审计行任何字段都不该包含消息内容
        assertThat(row.getModel()).doesNotContain("敏感");
    }

    @Test
    void failureAuditRowRecordsErrorStatus() {
        var captor = ArgumentCaptor.forClass(AiUsageEntity.class);
        service.recordFailure(registryEndpoint(0, 0), 77);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo("ERROR");
        assertThat(captor.getValue().getPromptTokens()).isZero();
    }

    @Test
    void auditPersistenceFailureNeverPropagates() {
        when(repository.save(any())).thenThrow(new IllegalStateException("db down"));
        assertThatCode(() -> service.recordFailure(registryEndpoint(0, 0), 1))
            .doesNotThrowAnyException();
    }
}
