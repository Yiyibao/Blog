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

    /** 4E：孤儿附件检测——标题 + 正文引用检查用（管理端一次性全量，量小可承受）。 */
    interface NoteRefRow {
        Long getId();
        String getTitle();
        String getMarkdownContent();
    }

    @Query("SELECT n.id as id, n.title as title, n.markdownContent as markdownContent FROM NoteEntity n")
    List<NoteRefRow> findAllRefRows();

    /** 3C：浏览量数据库端原子自增（IP+id 短窗去重在 Controller 层，不落 IP 明文；仅已发布计数）。 */
    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE NoteEntity n SET n.viewsCount = n.viewsCount + 1 WHERE n.id = :id AND n.status = com.yubai.blog.note.NoteStatus.PUBLISHED")
    int incrementViewsCount(@Param("id") long id);

    /** L-12：列表专用轻量行——正文列（markdown_content）不再随分页读出。 */
    interface NoteListRow {
        Long getId();
        String getTitle();
        String getFolder();
        NoteStatus getStatus();
        String getSourceFileName();
        int getWordCount();
        long getVersion();
        Instant getCreatedAt();
        Instant getUpdatedAt();
    }

    /** NB-5：图谱只需 id/标题/目录。 */
    interface NoteGraphRow {
        Long getId();
        String getTitle();
        String getFolder();
    }

    /** NB-5：搜索命中投影——摘要源用 SUBSTRING 截前 400 字符，不捞全文。 */
    interface NoteSearchRow {
        Long getId();
        String getTitle();
        String getFolder();
        String getExcerptSource();
    }

    Page<NoteListRow> findAllByOrderByUpdatedAtDesc(Pageable pageable);
    Page<NoteListRow> findAllByStatusOrderByUpdatedAtDesc(NoteStatus status, Pageable pageable);

    /** L-12：给一页笔记批量补标签（一次 IN 查询），行结构 [noteId, tag]。 */
    @Query("SELECT n.id, t FROM NoteEntity n JOIN n.tags t WHERE n.id IN :ids ORDER BY n.id")
    List<Object[]> findTagRows(@Param("ids") java.util.Collection<Long> ids);

    /** NB-5：图谱节点行（不载正文）。 */
    @Query("SELECT n.id as id, n.title as title, n.folder as folder FROM NoteEntity n WHERE n.status = com.yubai.blog.note.NoteStatus.PUBLISHED")
    List<NoteGraphRow> findPublishedGraphRows();

    /** NB-5：图谱标签边（[noteId, tag]，仅已发布）。 */
    @Query("SELECT n.id, t FROM NoteEntity n JOIN n.tags t WHERE n.status = com.yubai.blog.note.NoteStatus.PUBLISHED ORDER BY n.id")
    List<Object[]> findPublishedTagRows();

    @Query(value = """
        SELECT DISTINCT n.id as id, n.title as title, n.folder as folder,
               SUBSTRING(n.markdownContent, 1, 400) as excerptSource, n.updatedAt as updatedAt
        FROM NoteEntity n
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
    Page<NoteSearchRow> searchPublished(@Param("query") String query, Pageable pageable);
}
