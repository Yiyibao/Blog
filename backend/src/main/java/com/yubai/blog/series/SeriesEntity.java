package com.yubai.blog.series;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.yubai.blog.series.SeriesDtos.SeriesRequest;

/** 4B：合集——文章按主题成串（V11 表，乐观锁沿用 version 列）。 */
@Entity
@Table(name = "series")
public class SeriesEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false, unique = true, length = 200)
    private String slug;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "cover_image", length = 500)
    private String coverImage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SeriesStatus status = SeriesStatus.DRAFT;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected SeriesEntity() {
    }

    public static SeriesEntity create(SeriesRequest request) {
        var series = new SeriesEntity();
        series.update(request);
        return series;
    }

    public void update(SeriesRequest request) {
        this.name = request.name().trim();
        this.slug = request.slug().trim();
        this.description = request.description() == null ? "" : request.description().trim();
        this.coverImage = request.coverImage() == null || request.coverImage().isBlank()
            ? null : request.coverImage().trim();
        var nextStatus = request.status() == null ? SeriesStatus.DRAFT : request.status();
        if (nextStatus == SeriesStatus.PUBLISHED && this.status != SeriesStatus.PUBLISHED) {
            this.publishedAt = Instant.now();
        }
        this.status = nextStatus;
    }

    /** 成员变更也是一次合集编辑：显式置脏推进 @Version 与 updatedAt（字段无变化时 Hibernate 不会自动更新）。 */
    void touch() {
        this.updatedAt = Instant.now();
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
    public String getCoverImage() { return coverImage; }
    public SeriesStatus getStatus() { return status; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
