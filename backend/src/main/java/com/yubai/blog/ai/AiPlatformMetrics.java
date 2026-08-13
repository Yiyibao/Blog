package com.yubai.blog.ai;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * M7：为任务、受控字节和下载相关 SLO 提供低基数指标。
 *
 * <p>HTTP latency/status 与 Hikari pool 由 Spring Boot/Micrometer 自动提供；这里补上业务状态， 但不把 owner、task
 * id、file id 或正文放入 tag，避免指标成为隐私数据副本。
 */
@Component
public class AiPlatformMetrics implements MeterBinder {
    private final AiTaskRepository tasks;
    private final JdbcTemplate jdbcTemplate;

    public AiPlatformMetrics(AiTaskRepository tasks, JdbcTemplate jdbcTemplate) {
        this.tasks = tasks;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        for (var status : AiTaskStatus.values()) {
            Gauge.builder("blog.ai.tasks", tasks, repository -> repository.countByStatus(status))
                    .description("AI tasks by durable status")
                    .tag("status", status.name())
                    .register(registry);
        }
        Gauge.builder(
                        "blog.ai.files.bytes",
                        jdbcTemplate,
                        template -> sumBytes("ai_files", List.of("DELETED", "EXPIRED")))
                .description("Retained AI file bytes")
                .register(registry);
        Gauge.builder(
                        "blog.ai.artifacts.bytes",
                        jdbcTemplate,
                        template -> sumBytes("ai_artifacts", List.of("DELETED", "EXPIRED")))
                .description("Retained AI artifact bytes")
                .register(registry);
    }

    private double sumBytes(String table, List<String> excludedStatuses) {
        var placeholders = String.join(",", excludedStatuses.stream().map(value -> "?").toList());
        var value =
                jdbcTemplate.queryForObject(
                        "select coalesce(sum(size_bytes), 0) from "
                                + table
                                + " where status not in ("
                                + placeholders
                                + ")",
                        Long.class,
                        excludedStatuses.toArray());
        return value == null ? 0 : value.doubleValue();
    }
}
