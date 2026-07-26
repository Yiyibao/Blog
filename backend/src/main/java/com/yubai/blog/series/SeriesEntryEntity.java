package com.yubai.blog.series;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 4B：合集成员行——content_type 预留多类型（当前仅 POST），刻意不做 JPA 关联，
 * 标题/日期经批量查询补齐（沿用 kitchen 的独立仓库显式查询手法）。
 */
@Entity
@Table(name = "series_entries")
public class SeriesEntryEntity {

    public static final String TYPE_POST = "POST";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "series_id", nullable = false)
    private long seriesId;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(name = "content_id", nullable = false)
    private long contentId;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "chapter_title", length = 200)
    private String chapterTitle;

    protected SeriesEntryEntity() {
    }

    public static SeriesEntryEntity post(long seriesId, long postId, int sortOrder, String chapterTitle) {
        var entry = new SeriesEntryEntity();
        entry.seriesId = seriesId;
        entry.contentType = TYPE_POST;
        entry.contentId = postId;
        entry.sortOrder = sortOrder;
        entry.chapterTitle = chapterTitle == null || chapterTitle.isBlank() ? null : chapterTitle.trim();
        return entry;
    }

    public Long getId() { return id; }
    public long getSeriesId() { return seriesId; }
    public String getContentType() { return contentType; }
    public long getContentId() { return contentId; }
    public int getSortOrder() { return sortOrder; }
    public String getChapterTitle() { return chapterTitle; }
}
