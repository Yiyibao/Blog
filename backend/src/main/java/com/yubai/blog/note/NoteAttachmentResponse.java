package com.yubai.blog.note;

import java.time.Instant;
import java.util.UUID;

public record NoteAttachmentResponse(
    long id, UUID publicId, long noteId, String fileName, String mediaType,
    long byteSize, String url, Instant createdAt
) {
    static NoteAttachmentResponse from(NoteAttachmentEntity attachment) {
        return new NoteAttachmentResponse(attachment.getId(), attachment.getPublicId(), attachment.getNoteId(),
            attachment.getFileName(), attachment.getMediaType(), attachment.getByteSize(),
            "/api/v1/note-assets/" + attachment.getPublicId(), attachment.getCreatedAt());
    }

    static NoteAttachmentResponse from(NoteAttachmentRepository.AttachmentListRow row) {
        return new NoteAttachmentResponse(row.getId(), row.getPublicId(), row.getNoteId(),
            row.getFileName(), row.getMediaType(), row.getByteSize(),
            "/api/v1/note-assets/" + row.getPublicId(), row.getCreatedAt());
    }
}
