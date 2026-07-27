package com.yubai.blog.admin;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.note.NoteAttachmentResponse;
import com.yubai.blog.note.NoteAttachmentService;

@RestController
@RequestMapping("/api/v1/admin/notes/{noteId}/attachments")
public class AdminNoteAttachmentController {
    private final NoteAttachmentService service;
    public AdminNoteAttachmentController(NoteAttachmentService service) { this.service = service; }

    @GetMapping public ApiResponse<List<NoteAttachmentResponse>> list(@PathVariable long noteId) {
        return ApiResponse.ok(service.findForNote(noteId));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoteAttachmentResponse> upload(@PathVariable long noteId, @RequestParam("file") MultipartFile file) {
        return ApiResponse.created(service.upload(noteId, file));
    }

    @GetMapping("/{attachmentId}/content")
    public ResponseEntity<byte[]> read(@PathVariable long noteId, @PathVariable long attachmentId) {
        var data = service.readContent(noteId, attachmentId);
        var attachment = service.findForNote(noteId, attachmentId);
        // P1-6：管理端预览允许浏览器短时私有缓存，减少工作台反复读
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(attachment.getMediaType()))
            .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePrivate())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(attachment.getFileName()).build().toString())
            .body(data);
    }

    @DeleteMapping("/{attachmentId}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long noteId, @PathVariable long attachmentId) { service.delete(noteId, attachmentId); }
}
