package com.yubai.blog.music;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/music")
public class MusicTrackController {

    private final MusicTrackService service;

    public MusicTrackController(MusicTrackService service) {
        this.service = service;
    }

    @GetMapping("/tracks")
    public ApiResponse<List<MusicTrackResponse>> findAll() {
        return ApiResponse.ok(service.findAll());
    }
}
