package com.yubai.blog.post;

import com.yubai.blog.common.ApiResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/preview/posts")
public class PostPreviewController {
    private final PostPreviewService service;

    public PostPreviewController(PostPreviewService service) {
        this.service = service;
    }

    @GetMapping("/{postId}")
    public ResponseEntity<ApiResponse<PostResponse>> get(
            @PathVariable long postId, @RequestParam String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Robots-Tag", "noindex, nofollow, noarchive")
                .header("X-Content-Type-Options", "nosniff")
                .body(ApiResponse.ok(service.resolve(postId, token)));
    }
}
