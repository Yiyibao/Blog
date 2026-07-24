package com.yubai.blog.music;

public record MusicTrackResponse(
    String id,
    String title,
    String artist,
    int duration,
    String audioUrl,
    String coverUrl
) {
    public static MusicTrackResponse from(MusicTrackEntity track) {
        return new MusicTrackResponse(
            track.getTrackId(),
            track.getTitle(),
            track.getArtist(),
            track.getDuration(),
            track.getAudioUrl(),
            track.getCoverUrl()
        );
    }
}
