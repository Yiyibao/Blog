package com.yubai.blog.admin;

import java.nio.charset.StandardCharsets;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.note.NoteRequest;
import com.yubai.blog.note.NoteResponse;
import com.yubai.blog.note.NoteService;
import com.yubai.blog.note.NoteStatus;
import com.yubai.blog.note.NoteStatusChangeRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/notes")
public class AdminNoteController {
    private final NoteService service;
    public AdminNoteController(NoteService service) { this.service = service; }

    @GetMapping public ApiResponse<PageResponse<NoteResponse>> findAll(
        @RequestParam(required = false) NoteStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) { return ApiResponse.ok(service.findAll(status, page, size)); }
    @GetMapping("/{id}") public ApiResponse<NoteResponse> findOne(@PathVariable long id) { return ApiResponse.ok(service.findOne(id)); }

    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoteResponse> create(@Valid @RequestBody NoteRequest request) { return ApiResponse.ok(service.create(request)); }

    @PutMapping("/{id}")
    public ApiResponse<NoteResponse> update(@PathVariable long id, @Valid @RequestBody NoteRequest request) { return ApiResponse.ok(service.update(id, request)); }

    @PutMapping("/{id}/publish")
    public ApiResponse<NoteResponse> publish(@PathVariable long id, @Valid @RequestBody NoteStatusChangeRequest request) {
        return ApiResponse.ok(service.publish(id, request.version()));
    }

    @PutMapping("/{id}/unpublish")
    public ApiResponse<NoteResponse> unpublish(@PathVariable long id, @Valid @RequestBody NoteStatusChangeRequest request) {
        return ApiResponse.ok(service.unpublish(id, request.version()));
    }

    @PutMapping("/{id}/archive")
    public ApiResponse<NoteResponse> archive(@PathVariable long id, @Valid @RequestBody NoteStatusChangeRequest request) {
        return ApiResponse.ok(service.archive(id, request.version()));
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<NoteResponse> importMarkdown(@RequestParam("file") MultipartFile file) { return ApiResponse.ok(service.importMarkdown(file)); }

    @GetMapping(value = "/{id}/export", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<byte[]> export(@PathVariable long id) {
        var note = service.findOne(id);
        var filename = note.title().replaceAll("[\\r\\n\\\\/:*?\"<>|]", "_") + ".md";
        var headers = new HttpHeaders();
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build());
        headers.setContentType(new MediaType("text", "markdown", StandardCharsets.UTF_8));
        return new ResponseEntity<>(note.markdownContent().getBytes(StandardCharsets.UTF_8), headers, HttpStatus.OK);
    }

    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) { service.delete(id); }
}
