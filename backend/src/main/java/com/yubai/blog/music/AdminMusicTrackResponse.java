package com.yubai.blog.music;

import java.time.Instant;

/** 4F：管理端曲目 DTO——比公开 DTO 多数据库主键与排序/时间字段。 */
public record AdminMusicTrackResponse(
    long id,
    String trackId,
    String title,
    String artist,
    int duration,
    String audioUrl,
    String coverUrl,
    int sortOrder,
    Instant createdAt
) {
    public static AdminMusicTrackResponse from(MusicTrackEntity track) {
        return new AdminMusicTrackResponse(
            track.getId(), track.getTrackId(), track.getTitle(), track.getArtist(), track.getDuration(),
            track.getAudioUrl(), track.getCoverUrl(), track.getSortOrder(), track.getCreatedAt()
        );
    }
}
