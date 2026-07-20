package com.yubai.blog.note;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteAttachmentRepository extends JpaRepository<NoteAttachmentEntity, Long> {
    List<NoteAttachmentEntity> findAllByNoteIdOrderByCreatedAtDesc(long noteId);
    Optional<NoteAttachmentEntity> findByPublicId(UUID publicId);
}
