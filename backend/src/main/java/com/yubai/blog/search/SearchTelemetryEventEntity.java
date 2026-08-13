package com.yubai.blog.search;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "search_query_events")
public class SearchTelemetryEventEntity {
    @Id private UUID id;

    @Column(name = "query_hash", nullable = false, length = 64)
    private String queryHash;

    @Column(nullable = false, length = 16)
    private String scope;

    @Column(name = "result_count", nullable = false)
    private int resultCount;

    @Column(name = "zero_result", nullable = false)
    private boolean zeroResult;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(name = "clicked_position")
    private Integer clickedPosition;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected SearchTelemetryEventEntity() {}

    public static SearchTelemetryEventEntity create(
            String queryHash, String scope, int resultCount, int latencyMs) {
        var entity = new SearchTelemetryEventEntity();
        entity.id = UUID.randomUUID();
        entity.queryHash = queryHash;
        entity.scope = scope;
        entity.resultCount = resultCount;
        entity.zeroResult = resultCount == 0;
        entity.latencyMs = latencyMs;
        return entity;
    }

    public void markClick(int position) {
        if (clickedPosition == null) clickedPosition = position;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public String getQueryHash() {
        return queryHash;
    }

    public String getScope() {
        return scope;
    }

    public int getResultCount() {
        return resultCount;
    }

    public boolean isZeroResult() {
        return zeroResult;
    }

    public int getLatencyMs() {
        return latencyMs;
    }

    public Integer getClickedPosition() {
        return clickedPosition;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
