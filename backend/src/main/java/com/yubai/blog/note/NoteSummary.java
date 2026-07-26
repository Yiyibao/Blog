package com.yubai.blog.note;

import java.time.Instant;
import java.util.List;

/**
 * P1-2：笔记列表专用摘要 DTO——不含 markdownContent 正文。
 * 公开列表与管理端列表均返回本类型，正文由详情接口（NoteResponse）返回。
 */
public record NoteSummary(
    long id, String title, String folder, NoteStatus status,
    List<String> tags, String sourceFileName, int wordCount, long version,
    Instant createdAt, Instant updatedAt
) {
    static NoteSummary from(NoteEntity note) {
        return new NoteSummary(note.getId(), note.getTitle(), note.getFolder(), note.getStatus(),
            note.getTags(), note.getSourceFileName(), note.getWordCount(), note.getVersion(),
            note.getCreatedAt(), note.getUpdatedAt());
    }

    /** L-12：由轻量投影行 + 批量补取的标签组装，列表路径不再触碰正文列。 */
    static NoteSummary of(NoteRepository.NoteListRow row, List<String> tags) {
        return new NoteSummary(row.getId(), row.getTitle(), row.getFolder(), row.getStatus(),
            tags, row.getSourceFileName(), row.getWordCount(), row.getVersion(),
            row.getCreatedAt(), row.getUpdatedAt());
    }
}
