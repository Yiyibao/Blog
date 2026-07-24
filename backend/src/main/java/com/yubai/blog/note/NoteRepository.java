package com.yubai.blog.note;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NoteRepository extends JpaRepository<NoteEntity, Long> {

    interface NoteSitemapProjection {
        Long getId();
        Instant getUpdatedAt();
    }

    @Query("SELECT n.id as id, n.updatedAt as updatedAt FROM NoteEntity n WHERE n.status = com.yubai.blog.note.NoteStatus.PUBLISHED")
    List<NoteSitemapProjection> findPublishedSitemap();
    Page<NoteEntity> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    Page<NoteEntity> findAllByStatusOrderByUpdatedAtDesc(NoteStatus status, Pageable pageable);

    @Query(value = """
        SELECT DISTINCT n FROM NoteEntity n
        LEFT JOIN n.tags tag
        WHERE n.status = com.yubai.blog.note.NoteStatus.PUBLISHED
          AND (LOWER(n.title) LIKE LOWER(:query)
            OR LOWER(n.folder) LIKE LOWER(:query)
            OR LOWER(n.markdownContent) LIKE LOWER(:query)
            OR LOWER(tag) LIKE LOWER(:query))
        ORDER BY n.updatedAt DESC
        """, countQuery = """
        SELECT COUNT(DISTINCT n) FROM NoteEntity n
        LEFT JOIN n.tags tag
        WHERE n.status = com.yubai.blog.note.NoteStatus.PUBLISHED
          AND (LOWER(n.title) LIKE LOWER(:query)
            OR LOWER(n.folder) LIKE LOWER(:query)
            OR LOWER(n.markdownContent) LIKE LOWER(:query)
            OR LOWER(tag) LIKE LOWER(:query))
        """)
    Page<NoteEntity> searchPublished(@Param("query") String query, Pageable pageable);
}
