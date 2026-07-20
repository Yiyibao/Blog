package com.yubai.blog.note;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface NoteRepository extends JpaRepository<NoteEntity, Long> {
    List<NoteEntity> findAllByOrderByUpdatedAtDesc();
    List<NoteEntity> findAllByStatusOrderByUpdatedAtDesc(NoteStatus status);
}
