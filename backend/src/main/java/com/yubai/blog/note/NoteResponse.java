package com.yubai.blog.note;

import java.time.Instant;
import java.util.List;

public record NoteResponse(
    long id, String title, String markdownContent, String folder, NoteStatus status,
    List<String> tags, String sourceFileName, int wordCount, long version,
    int viewsCount,
    Instant createdAt, Instant updatedAt
) {
    static NoteResponse from(NoteEntity note) {
        return new NoteResponse(note.getId(), note.getTitle(), note.getMarkdownContent(), note.getFolder(),
            note.getStatus(), note.getTags(), note.getSourceFileName(), note.getWordCount(), note.getVersion(),
            note.getViewsCount(),
            note.getCreatedAt(), note.getUpdatedAt());
    }
}
