package com.yubai.blog.search;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores only aggregate-safe search telemetry; raw queries and content never enter this table. */
@Service
public class SearchTelemetryService {
    private static final int MAX_LATENCY_MS = 30_000;
    private final SearchTelemetryEventRepository repository;

    public SearchTelemetryService(SearchTelemetryEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public UUID record(String query, String scope, int resultCount, long elapsedNanos) {
        var normalized = SearchQueryNormalizer.normalize(query);
        if (normalized.isBlank()) return null;
        var safeScope =
                switch (scope == null ? "PUBLIC" : scope.trim().toUpperCase()) {
                    case "PRIVATE", "AI" -> scope.trim().toUpperCase();
                    default -> "PUBLIC";
                };
        var latencyMs = (int) Math.max(0, Math.min(MAX_LATENCY_MS, elapsedNanos / 1_000_000L));
        var entity =
                SearchTelemetryEventEntity.create(
                        sha256(normalized), safeScope, Math.max(0, resultCount), latencyMs);
        return repository.save(entity).getId();
    }

    @Transactional
    public void recordClick(UUID eventId, int position) {
        if (eventId == null || position < 1 || position > 100) return;
        repository.findById(eventId).ifPresent(event -> event.markClick(position));
    }

    static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
