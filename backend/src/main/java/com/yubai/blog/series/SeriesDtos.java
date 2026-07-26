package com.yubai.blog.series;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 4B：合集全部读写契约。 */
public final class SeriesDtos {

    private SeriesDtos() {
    }

    public record SeriesEntryItem(
        long postId,
        String slug,
        String title,
        String date,
        String chapterTitle,
        int position
    ) {}

    /** 管理端合集 DTO。 */
    public record AdminSeriesResponse(
        long id, String name, String slug, String description, String coverImage,
        SeriesStatus status, long version, int entryCount,
        Instant createdAt, Instant updatedAt, Instant publishedAt,
        List<SeriesEntryItem> entries
    ) {}

    /** 公开合集摘要（列表）。 */
    public record PublicSeriesSummary(
        String slug, String name, String description, String coverImage,
        int entryCount, Instant publishedAt
    ) {}

    /** 公开合集详情（按序成员）。 */
    public record PublicSeriesDetail(
        String slug, String name, String description, String coverImage,
        Instant publishedAt, List<SeriesEntryItem> entries
    ) {}

    /** 文章详情里的「本文属于合集 X（n/N）」。 */
    public record SeriesRef(String slug, String name, int position, int total) {}

    public record SeriesRequest(
        @NotBlank @Size(max = 200) String name,
        @NotBlank @Size(max = 200) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
        @Size(max = 5000) String description,
        @Size(max = 500) String coverImage,
        SeriesStatus status
    ) {}

    public record SeriesEntriesRequest(
        @NotNull List<@NotNull EntryInput> entries,
        long version
    ) {
        public record EntryInput(long postId, @Size(max = 200) String chapterTitle) {}
    }
}
