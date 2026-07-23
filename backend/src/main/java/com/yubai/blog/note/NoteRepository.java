package com.yubai.blog.note;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<NoteEntity, Long> {
    Page<NoteEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    Page<NoteEntity> findAllByStatusOrderByUpdatedAtDesc(NoteStatus status, Pageable pageable);
}
