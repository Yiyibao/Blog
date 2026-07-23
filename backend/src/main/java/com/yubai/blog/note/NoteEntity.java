package com.yubai.blog.note;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "learning_notes")
public class NoteEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(name = "markdown_content", nullable = false, columnDefinition = "text")
    private String markdownContent;

    @Column(nullable = false, length = 100)
    private String folder;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NoteStatus status;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "learning_note_tags", joinColumns = @JoinColumn(name = "note_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "tag", nullable = false, length = 80)
    private List<String> tags = new ArrayList<>();

    @Column(name = "source_file_name", length = 255)
    private String sourceFileName;

    @Column(name = "word_count", nullable = false)
    private int wordCount;

    @Version
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected NoteEntity() {}

    public static NoteEntity create(NoteRequest request) {
        var note = new NoteEntity();
        note.status = NoteStatus.DRAFT;
        note.update(request);
        return note;
    }

    public static NoteEntity imported(String title, String markdown, String sourceFileName) {
        var note = new NoteEntity();
        note.title = title;
        note.markdownContent = markdown;
        note.folder = "导入笔记";
        note.status = NoteStatus.DRAFT;
        note.sourceFileName = sourceFileName;
        note.wordCount = countWords(markdown);
        return note;
    }

    public void update(NoteRequest request) {
        title = request.title().trim();
        markdownContent = request.markdownContent();
        folder = request.folder().trim();
        tags.clear();
        tags.addAll(request.tags().stream().map(String::trim).filter(tag -> !tag.isBlank()).distinct().toList());
        wordCount = countWords(markdownContent);
    }

    public void changeStatus(NoteStatus nextStatus) {
        status = nextStatus;
    }

    private static int countWords(String markdown) {
        var plain = markdown.replaceAll("(?m)^#{1,6}\\s+|[`>*_~\\[\\]()-]", " ").trim();
        if (plain.isBlank()) return 0;
        var latin = plain.replaceAll("[\\p{IsHan}]", " ").trim();
        int latinWords = latin.isBlank() ? 0 : latin.split("\\s+").length;
        int hanChars = (int) plain.codePoints().filter(cp -> Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN).count();
        return latinWords + hanChars;
    }

    @PrePersist
    void created() { createdAt = updatedAt = Instant.now(); }

    @PreUpdate
    void updated() { updatedAt = Instant.now(); }

    public Long getId() { return id; }
    public String getTitle() { return title; }
    public String getMarkdownContent() { return markdownContent; }
    public String getFolder() { return folder; }
    public NoteStatus getStatus() { return status; }
    public List<String> getTags() { return List.copyOf(tags); }
    public String getSourceFileName() { return sourceFileName; }
    public int getWordCount() { return wordCount; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
