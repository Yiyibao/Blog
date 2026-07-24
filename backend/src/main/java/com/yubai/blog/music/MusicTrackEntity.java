package com.yubai.blog.music;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "music_tracks")
public class MusicTrackEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "track_id", nullable = false, unique = true, length = 60)
    private String trackId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 120)
    private String artist;

    @Column(nullable = false)
    private int duration;

    @Column(name = "audio_url", nullable = false, columnDefinition = "text")
    private String audioUrl;

    @Column(name = "cover_url", nullable = false, columnDefinition = "text")
    private String coverUrl;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MusicTrackEntity() {
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTrackId() { return trackId; }
    public String getTitle() { return title; }
    public String getArtist() { return artist; }
    public int getDuration() { return duration; }
    public String getAudioUrl() { return audioUrl; }
    public String getCoverUrl() { return coverUrl; }
    public int getSortOrder() { return sortOrder; }
    public Instant getCreatedAt() { return createdAt; }
}
