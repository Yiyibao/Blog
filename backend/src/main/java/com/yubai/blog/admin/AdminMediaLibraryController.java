package com.yubai.blog.admin;

import com.yubai.blog.common.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/library/media")
public class AdminMediaLibraryController {
    private final MediaLibraryService service;

    public AdminMediaLibraryController(MediaLibraryService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<MediaLibraryService.MediaItem>> list(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(service.list(sourceType, status));
    }
}
