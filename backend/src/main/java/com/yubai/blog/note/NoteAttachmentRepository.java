package com.yubai.blog.note;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface NoteAttachmentRepository extends JpaRepository<NoteAttachmentEntity, Long> {
    List<NoteAttachmentEntity> findAllByNoteIdOrderByCreatedAtDesc(long noteId);

    Optional<NoteAttachmentEntity> findByPublicIdAndDeletedAtIsNull(UUID publicId);

    Optional<NoteAttachmentEntity> findByIdAndDeletedAtIsNull(long id);

    List<NoteAttachmentEntity> findByDeletedAtBefore(Instant cutoff);

    /** 6B：列表投影——绝不 SELECT content 字节列。 */
    interface AttachmentListRow {
        Long getId();

        UUID getPublicId();

        long getNoteId();

        String getFileName();

        String getMediaType();

        long getByteSize();

        String getStorageKey();

        Instant getCreatedAt();
    }

    @Query(
            """
        SELECT a.id as id, a.publicId as publicId, a.noteId as noteId, a.fileName as fileName,
               a.mediaType as mediaType, a.byteSize as byteSize, a.storageKey as storageKey,
               a.createdAt as createdAt
        FROM NoteAttachmentEntity a WHERE a.noteId = :noteId AND a.deletedAt IS NULL ORDER BY a.createdAt DESC
        """)
    List<AttachmentListRow> findListRowsByNoteId(long noteId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE NoteAttachmentEntity a SET a.storageKey = :storageKey WHERE a.id = :id AND a.storageKey IS NULL")
    int claimStorageKey(long id, String storageKey);

    @Modifying
    @Query("delete from NoteAttachmentEntity a where a.noteId = :noteId")
    int deleteByNoteId(long noteId);

    /** 4D/4E：总览行与容量聚合——绝不 SELECT content 字节列。 */
    interface AttachmentAdminRow {
        Long getId();

        UUID getPublicId();

        long getNoteId();

        String getFileName();

        String getMediaType();

        long getByteSize();

        String getStorageKey();

        Instant getCreatedAt();
    }

    @Query(
            """
        SELECT a.id as id, a.publicId as publicId, a.noteId as noteId, a.fileName as fileName,
               a.mediaType as mediaType, a.byteSize as byteSize, a.storageKey as storageKey,
               a.createdAt as createdAt
        FROM NoteAttachmentEntity a WHERE a.deletedAt IS NULL ORDER BY a.createdAt DESC, a.id DESC
        """)
    List<AttachmentAdminRow> findAdminRows();

    interface StorageAggregate {
        long getCnt();

        long getBytes();
    }

    @Query(
            "SELECT COUNT(a) as cnt, COALESCE(SUM(a.byteSize), 0) as bytes FROM NoteAttachmentEntity a WHERE a.deletedAt IS NULL")
    StorageAggregate aggregateStorage();
}
