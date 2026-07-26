package com.yubai.blog.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.music.AdminMusicTrackResponse;
import com.yubai.blog.music.MusicTrackRequest;
import com.yubai.blog.music.MusicTrackService;
import com.yubai.blog.quote.AdminQuoteResponse;
import com.yubai.blog.quote.QuoteRequest;
import com.yubai.blog.quote.QuoteService;

import jakarta.validation.Valid;

/** 4F/L-1：曲目与语录管理——不改迁移即可增删改（写操作 evict 对应公开缓存）。 */
@RestController
@RequestMapping("/api/v1/admin/library")
public class AdminLibraryController {

    private final MusicTrackService musicService;
    private final QuoteService quoteService;

    public AdminLibraryController(MusicTrackService musicService, QuoteService quoteService) {
        this.musicService = musicService;
        this.quoteService = quoteService;
    }

    // ── 曲目 ────────────────────────────────────────────────────────────────

    @GetMapping("/tracks")
    public ApiResponse<List<AdminMusicTrackResponse>> tracks() {
        return ApiResponse.ok(musicService.findAdmin());
    }

    @PostMapping("/tracks")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminMusicTrackResponse> createTrack(@Valid @RequestBody MusicTrackRequest request) {
        return ApiResponse.created(musicService.create(request));
    }

    @PutMapping("/tracks/{id}")
    public ApiResponse<AdminMusicTrackResponse> updateTrack(@PathVariable long id, @Valid @RequestBody MusicTrackRequest request) {
        return ApiResponse.ok(musicService.update(id, request));
    }

    @DeleteMapping("/tracks/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTrack(@PathVariable long id) {
        musicService.delete(id);
    }

    // ── 语录 ────────────────────────────────────────────────────────────────

    @GetMapping("/quotes")
    public ApiResponse<List<AdminQuoteResponse>> quotes() {
        return ApiResponse.ok(quoteService.findAdmin());
    }

    @PostMapping("/quotes")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminQuoteResponse> createQuote(@Valid @RequestBody QuoteRequest request) {
        return ApiResponse.created(quoteService.create(request));
    }

    @PutMapping("/quotes/{id}")
    public ApiResponse<AdminQuoteResponse> updateQuote(@PathVariable long id, @Valid @RequestBody QuoteRequest request) {
        return ApiResponse.ok(quoteService.update(id, request));
    }

    @DeleteMapping("/quotes/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteQuote(@PathVariable long id) {
        quoteService.delete(id);
    }
}
