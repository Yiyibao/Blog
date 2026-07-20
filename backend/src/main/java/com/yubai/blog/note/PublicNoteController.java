package com.yubai.blog.note;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/notes")
public class PublicNoteController {
    private final NoteService service;
    public PublicNoteController(NoteService service) { this.service = service; }

    @GetMapping
    public ApiResponse<List<NoteResponse>> findPublished() { return ApiResponse.ok(service.findPublished()); }
}
