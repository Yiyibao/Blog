package com.yubai.blog.note;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "note_attachments")
public class NoteAttachmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, unique = true)
    private UUID publicId;

    @Column(name = "note_id", nullable = false)
    private long noteId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "media_type", nullable = false, length = 100)
    private String mediaType;

    @Column(name = "byte_size", nullable = false)
    private long byteSize;

    @Column(columnDefinition = "bytea")
    private byte[] content;

    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected NoteAttachmentEntity() {}

    static NoteAttachmentEntity create(
            long noteId, String fileName, String mediaType, byte[] content) {
        var attachment = new NoteAttachmentEntity();
        attachment.publicId = UUID.randomUUID();
        attachment.noteId = noteId;
        attachment.fileName = fileName;
        attachment.mediaType = mediaType;
        attachment.byteSize = content.length;
        attachment.content = content;
        return attachment;
    }

    static NoteAttachmentEntity createWithStorage(
            long noteId, String fileName, String mediaType, long byteSize, String storageKey) {
        var attachment = new NoteAttachmentEntity();
        attachment.publicId = UUID.randomUUID();
        attachment.noteId = noteId;
        attachment.fileName = fileName;
        attachment.mediaType = mediaType;
        attachment.byteSize = byteSize;
        attachment.storageKey = storageKey;
        return attachment;
    }

    @PrePersist
    void created() {
        createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public UUID getPublicId() {
        return publicId;
    }

    public long getNoteId() {
        return noteId;
    }

    public String getFileName() {
        return fileName;
    }

    public String getMediaType() {
        return mediaType;
    }

    public long getByteSize() {
        return byteSize;
    }

    public byte[] getContent() {
        return content;
    }

    public String getStorageKey() {
        return storageKey;
    }

    void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    void setContent(byte[] content) {
        this.content = content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    void moveToTrash() {
        deletedAt = Instant.now();
    }

    void restore() {
        deletedAt = null;
    }
}
