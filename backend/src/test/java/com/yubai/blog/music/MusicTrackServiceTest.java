package com.yubai.blog.music;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MusicTrackServiceTest {

    @Mock
    MusicTrackRepository repository;

    @InjectMocks
    MusicTrackService service;

    private MusicTrackEntity mockTrack(String trackId, String title, String artist, int duration) {
        return new MusicTrackEntity() {
            @Override public String getTrackId() { return trackId; }
            @Override public String getTitle() { return title; }
            @Override public String getArtist() { return artist; }
            @Override public int getDuration() { return duration; }
            @Override public String getAudioUrl() { return "https://cdn.example.com/music/" + trackId + ".mp3"; }
            @Override public String getCoverUrl() { return "https://cdn.example.com/music/covers/" + trackId + ".jpg"; }
            @Override public int getSortOrder() { return 1; }
        };
    }

    @Test
    void findAllReturnsAllTracksOrdered() {
        var track1 = mockTrack("track-1", "雨的印记 (Kiss the Rain)", "钢琴纯音乐", 220);
        var track2 = mockTrack("track-2", "安妮的仙境 (Annie's Wonderland)", "舒缓吉他与长笛", 240);
        when(repository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of(track1, track2));

        var result = service.findAll();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo("track-1");
        assertThat(result.get(0).title()).isEqualTo("雨的印记 (Kiss the Rain)");
        assertThat(result.get(0).artist()).isEqualTo("钢琴纯音乐");
        assertThat(result.get(0).duration()).isEqualTo(220);
        assertThat(result.get(0).audioUrl()).isEqualTo("https://cdn.example.com/music/track-1.mp3");
        assertThat(result.get(0).coverUrl()).isEqualTo("https://cdn.example.com/music/covers/track-1.jpg");
    }

    @Test
    void findAllReturnsEmptyListWhenNoTracks() {
        when(repository.findAllByOrderBySortOrderAscIdAsc()).thenReturn(List.of());
        var result = service.findAll();
        assertThat(result).isEmpty();
    }
}
