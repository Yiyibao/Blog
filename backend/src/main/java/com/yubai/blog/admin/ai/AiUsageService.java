package com.yubai.blog.admin.ai;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 4A-6：AI 用量审计与日预算。
 *
 * <ul>
 *   <li>审计：每次调用（成败皆记）写 ai_usage 元数据行，绝不存消息内容。
 *   <li>预算：注册表供应商按 daily_request_limit / daily_token_limit 检查当日（Asia/Shanghai）用量，超限 429。
 *   <li>env 回退端点（providerId=null）两者皆跳过。
 * </ul>
 */
@Service
public class AiUsageService {

    private static final Logger log = LoggerFactory.getLogger(AiUsageService.class);
    private static final ZoneId SITE_ZONE = ZoneId.of("Asia/Shanghai");

    static final String STATUS_OK = "OK";
    static final String STATUS_ERROR = "ERROR";

    private final AiUsageRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final ThreadLocal<Integer> currentReservation = new ThreadLocal<>();

    @Autowired
    public AiUsageService(AiUsageRepository repository, JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
    }

    AiUsageService(AiUsageRepository repository) {
        this(repository, null);
    }

    /** 调用前的日预算闸门——超限抛 429，不产生出网请求。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assertWithinDailyBudget(AiEndpoint endpoint) {
        assertWithinDailyBudget(endpoint, 0);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void assertWithinDailyBudget(AiEndpoint endpoint, int estimatedInputTokens) {
        if (endpoint.providerId() == null) {
            return;
        }
        if (jdbcTemplate != null) {
            var reservedTokens =
                    Math.max(1, endpoint.maxOutputTokens() + Math.max(0, estimatedInputTokens));
            var updated =
                    jdbcTemplate.update(
                            """
                INSERT INTO ai_daily_budgets
                    (provider_id, usage_date, request_count, reserved_tokens, updated_at)
                VALUES (?, ?, 1, ?, now())
                ON CONFLICT (provider_id, usage_date) DO UPDATE SET
                    request_count = ai_daily_budgets.request_count + 1,
                    reserved_tokens = ai_daily_budgets.reserved_tokens + EXCLUDED.reserved_tokens,
                    updated_at = now()
                WHERE (? <= 0 OR ai_daily_budgets.request_count + 1 <= ?)
                  AND (? <= 0 OR ai_daily_budgets.prompt_tokens + ai_daily_budgets.completion_tokens
                       + ai_daily_budgets.reserved_tokens + EXCLUDED.reserved_tokens <= ?)
                """,
                            endpoint.providerId(),
                            LocalDate.now(SITE_ZONE),
                            reservedTokens,
                            endpoint.dailyRequestLimit(),
                            endpoint.dailyRequestLimit(),
                            endpoint.dailyTokenLimit(),
                            endpoint.dailyTokenLimit());
            if (updated == 0) {
                throw new AiServiceException(HttpStatus.TOO_MANY_REQUESTS, "该供应商今日预算已达限额，明日自动恢复");
            }
            currentReservation.set(reservedTokens);
            return;
        }
        var usage = repository.aggregateSince(endpoint.providerId(), startOfTodaySiteZone());
        if (endpoint.dailyRequestLimit() > 0
                && usage.getRequests() >= endpoint.dailyRequestLimit()) {
            throw new AiServiceException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "该供应商今日请求数已达限额（" + endpoint.dailyRequestLimit() + "），明日自动恢复");
        }
        if (endpoint.dailyTokenLimit() > 0 && usage.getTokens() >= endpoint.dailyTokenLimit()) {
            throw new AiServiceException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "该供应商今日 token 用量已达限额（" + endpoint.dailyTokenLimit() + "），明日自动恢复");
        }
    }

    /** 成功调用落审计行（token 数取响应 usage，缺失记 0）。REQUIRES_NEW：审计写入失败不影响主流程结果。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(AiEndpoint endpoint, ChatResponse response, long latencyMs) {
        record(endpoint, response, latencyMs, STATUS_OK);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(AiEndpoint endpoint, long latencyMs) {
        record(endpoint, null, latencyMs, STATUS_ERROR);
    }

    private void record(AiEndpoint endpoint, ChatResponse response, long latencyMs, String status) {
        if (endpoint.providerId() == null) {
            return;
        }
        try {
            int promptTokens =
                    response != null && response.usage() != null
                            ? response.usage().promptTokens()
                            : 0;
            int completionTokens =
                    response != null && response.usage() != null
                            ? response.usage().completionTokens()
                            : 0;
            if (jdbcTemplate != null) {
                jdbcTemplate.update(
                        """
                    UPDATE ai_daily_budgets SET
                        reserved_tokens = greatest(0, reserved_tokens - ?),
                        prompt_tokens = prompt_tokens + ?,
                        completion_tokens = completion_tokens + ?,
                        error_count = error_count + ?,
                        total_latency_ms = total_latency_ms + ?,
                        updated_at = now()
                    WHERE provider_id = ? AND usage_date = ?
                    """,
                        currentReservation.get() == null
                                ? Math.max(1, endpoint.maxOutputTokens())
                                : currentReservation.get(),
                        promptTokens,
                        completionTokens,
                        STATUS_ERROR.equals(status) ? 1 : 0,
                        Math.max(0, latencyMs),
                        endpoint.providerId(),
                        LocalDate.now(SITE_ZONE));
            }
            var model =
                    response != null && response.model() != null
                            ? response.model()
                            : endpoint.model();
            repository.save(
                    AiUsageEntity.create(
                            endpoint.providerId(),
                            model,
                            promptTokens,
                            completionTokens,
                            (int) Math.min(latencyMs, Integer.MAX_VALUE),
                            status));
        } catch (Exception exception) {
            // 审计是旁路——写入失败只记日志，绝不影响对话主流程
            log.warn("ai_usage 审计写入失败: {}", exception.toString());
        } finally {
            currentReservation.remove();
        }
    }

    /** 4D 仪表盘用量卡片数据：窗口内按供应商聚合。 */
    public List<AiUsageRepository.UsageSummaryRow> summarize(int days) {
        var since =
                LocalDate.now(SITE_ZONE)
                        .minusDays(Math.max(0, days - 1))
                        .atStartOfDay(SITE_ZONE)
                        .toInstant();
        return repository.summarizeSince(since);
    }

    private static java.time.Instant startOfTodaySiteZone() {
        return LocalDate.now(SITE_ZONE).atStartOfDay(SITE_ZONE).toInstant();
    }
}
