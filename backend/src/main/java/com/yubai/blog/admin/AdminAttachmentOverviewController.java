package com.yubai.blog.admin;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.note.NoteAttachmentRepository;
import com.yubai.blog.note.NoteRepository;

/**
 * 4E：附件总览——全站附件一屏（不读字节列），孤儿标记 = 所属笔记正文不再引用
 * 且创建超 7 天（新传未插入的给宽限期）。删除复用既有 /admin/notes/{noteId}/attachments/{id}。
 */
@RestController
@RequestMapping("/api/v1/admin/attachments")
public class AdminAttachmentOverviewController {

    static final Duration ORPHAN_GRACE = Duration.ofDays(7);

    private final NoteAttachmentRepository attachmentRepository;
    private final NoteRepository noteRepository;

    public AdminAttachmentOverviewController(NoteAttachmentRepository attachmentRepository,
                                             NoteRepository noteRepository) {
        this.attachmentRepository = attachmentRepository;
        this.noteRepository = noteRepository;
    }

    @GetMapping
    public ApiResponse<Overview> list() {
        var notes = new HashMap<Long, NoteRepository.NoteRefRow>();
        for (var note : noteRepository.findAllRefRows()) {
            notes.put(note.getId(), note);
        }
        var graceCutoff = Instant.now().minus(ORPHAN_GRACE);
        long totalBytes = 0;
        long orphanCount = 0;
        var items = new java.util.ArrayList<Item>();
        for (var row : attachmentRepository.findAdminRows()) {
            var note = notes.get(row.getNoteId());
            boolean referenced = note != null && note.getMarkdownContent() != null
                && note.getMarkdownContent().contains(row.getPublicId().toString());
            boolean orphan = !referenced && row.getCreatedAt().isBefore(graceCutoff);
            totalBytes += row.getByteSize();
            if (orphan) orphanCount++;
            items.add(new Item(row.getId(), row.getNoteId(),
                note == null ? "（笔记已删除）" : note.getTitle(),
                row.getFileName(), row.getMediaType(), row.getByteSize(),
                "/api/v1/note-assets/" + row.getPublicId(), row.getCreatedAt(), orphan));
        }
        return ApiResponse.ok(new Overview(items.size(), totalBytes, orphanCount, List.copyOf(items)));
    }

    public record Item(long id, long noteId, String noteTitle, String fileName, String mediaType,
                       long byteSize, String url, Instant createdAt, boolean orphan) {}

    public record Overview(int count, long totalBytes, long orphanCount, List<Item> items) {}
}
