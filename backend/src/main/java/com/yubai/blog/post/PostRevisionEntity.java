package com.yubai.blog.post;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * 4C：文章版本快照——只存正文相关字段（title/excerpt/content/markdown/格式），
 * meta（分类/标签/日期等）不入版本；content 在原保存时已消毒，恢复直接回写。
 */
@Entity
@Table(name = "post_revisions")
public class PostRevisionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "post_id", nullable = false)
    private long postId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String excerpt;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(name = "markdown_content", columnDefinition = "text")
    private String markdownContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_format", nullable = false, length = 16)
    private ContentFormat contentFormat = ContentFormat.HTML;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected PostRevisionEntity() {
    }

    /** 快照保存后的落库状态（content 已经消毒管线）。 */
    public static PostRevisionEntity snapshot(PostEntity post) {
        var revision = new PostRevisionEntity();
        revision.postId = post.getId();
        revision.title = post.getTitle();
        revision.excerpt = post.getExcerpt();
        revision.content = post.getContent();
        revision.markdownContent = post.getMarkdownContent();
        revision.contentFormat = post.getContentFormat();
        revision.createdAt = Instant.now();
        return revision;
    }

    public Long getId() { return id; }
    public long getPostId() { return postId; }
    public String getTitle() { return title; }
    public String getExcerpt() { return excerpt; }
    public String getContent() { return content; }
    public String getMarkdownContent() { return markdownContent; }
    public ContentFormat getContentFormat() { return contentFormat; }
    public Instant getCreatedAt() { return createdAt; }
}
