package com.yubai.blog.note;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import com.yubai.blog.common.PageResponse;

@RestController
@RequestMapping("/api/v1/notes")
@Validated
public class PublicNoteController {
    private final NoteService service;
    public PublicNoteController(NoteService service) { this.service = service; }

    /** P1-2：列表只出摘要（不含正文），正文经 /{id} 详情获取。 */
    @GetMapping
    public ApiResponse<PageResponse<NoteSummary>> findPublished(
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size
    ) { return ApiResponse.ok(service.findPublished(page, size)); }

    @GetMapping("/{id}")
    public ApiResponse<NoteResponse> findPublishedOne(@PathVariable long id) {
        return ApiResponse.ok(service.findPublishedOne(id));
    }
}
