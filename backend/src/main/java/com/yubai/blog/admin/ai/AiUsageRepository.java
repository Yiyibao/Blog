package com.yubai.blog.admin.ai;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiUsageRepository extends JpaRepository<AiUsageEntity, Long> {

    /** 4A-6：预算检查用的窗口聚合（请求数 + token 总量）。 */
    interface UsageAggregate {
        long getRequests();
        long getTokens();
    }

    @Query("""
        SELECT COUNT(u) as requests, COALESCE(SUM(u.promptTokens + u.completionTokens), 0) as tokens
        FROM AiUsageEntity u
        WHERE u.providerId = :providerId AND u.createdAt >= :since
        """)
    UsageAggregate aggregateSince(@Param("providerId") long providerId, @Param("since") Instant since);

    /** 4A-6/4D：仪表盘用量卡片——按供应商聚合的窗口汇总。 */
    interface UsageSummaryRow {
        long getProviderId();
        long getRequests();
        long getPromptTokens();
        long getCompletionTokens();
        long getErrors();
    }

    @Query("""
        SELECT u.providerId as providerId, COUNT(u) as requests,
               COALESCE(SUM(u.promptTokens), 0) as promptTokens,
               COALESCE(SUM(u.completionTokens), 0) as completionTokens,
               COALESCE(SUM(CASE WHEN u.status <> 'OK' THEN 1 ELSE 0 END), 0) as errors
        FROM AiUsageEntity u
        WHERE u.createdAt >= :since
        GROUP BY u.providerId
        ORDER BY u.providerId
        """)
    List<UsageSummaryRow> summarizeSince(@Param("since") Instant since);
}
