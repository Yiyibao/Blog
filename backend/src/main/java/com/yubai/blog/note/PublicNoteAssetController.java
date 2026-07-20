package com.yubai.blog.note;

import java.time.Duration;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/note-assets")
public class PublicNoteAssetController {
    private final NoteAttachmentService service;
    public PublicNoteAssetController(NoteAttachmentService service) { this.service = service; }

    @GetMapping("/{publicId}")
    public ResponseEntity<byte[]> read(@PathVariable UUID publicId) {
        var attachment = service.findPublic(publicId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(attachment.getMediaType()))
            .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic().immutable())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
                .filename(attachment.getFileName(), StandardCharsets.UTF_8).build().toString())
            .body(attachment.getContent());
    }
}
