package com.yubai.blog.ai;

import com.yubai.blog.search.SearchRequest;
import com.yubai.blog.search.SearchResult;
import com.yubai.blog.search.SearchService;
import com.yubai.blog.search.SearchTelemetryService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * AI-facing search facade: published content plus private notes only for the authenticated owner.
 */
@Service
public class AiReadOnlySearchService {
    private static final int MAX_RESULTS = 20;

    private final SearchService searchService;
    private final SearchTelemetryService telemetryService;

    public AiReadOnlySearchService(
            SearchService searchService, SearchTelemetryService telemetryService) {
        this.searchService = searchService;
        this.telemetryService = telemetryService;
    }

    public SearchResponse search(String owner, SearchRequest request) {
        var started = System.nanoTime();
        var includePrivateNotes = owner != null && !owner.isBlank();
        var result = searchService.search(request, includePrivateNotes);
        var telemetryId =
                telemetryService.record(
                        request.query(),
                        "AI",
                        Math.toIntExact(Math.min(Integer.MAX_VALUE, result.totalElements())),
                        System.nanoTime() - started);
        var sources =
                result.results().stream()
                        .limit(MAX_RESULTS)
                        .map(AiReadOnlySearchService::toSource)
                        .toList();
        return new SearchResponse(result.type(), result.totalElements(), sources, telemetryId);
    }

    private static Source toSource(SearchResult result) {
        return new Source(result.type(), result.id(), result.title(), result.url());
    }

    public record SearchResponse(String type, long total, List<Source> sources, UUID telemetryId) {}

    public record Source(String type, long id, String title, String url) {}

    public static SearchRequest request(
            String query,
            String type,
            int page,
            int size,
            String category,
            String tag,
            LocalDate from,
            LocalDate to) {
        var searchType =
                type == null || type.isBlank()
                        ? com.yubai.blog.search.SearchType.ALL
                        : com.yubai.blog.search.SearchType.valueOf(type.trim().toUpperCase());
        return new SearchRequest(query, searchType, page, size, category, null, tag, from, to);
    }
}
