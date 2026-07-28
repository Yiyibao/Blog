package com.yubai.blog.post;

import java.time.LocalDate;
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
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "posts")
public class PostEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String excerpt;

    @Column(name = "published_date", nullable = false)
    private LocalDate date;

    @Column(name = "read_time", nullable = false)
    private int readTime;

    @Column(nullable = false, length = 80)
    private String category;

    @Column(name = "category_slug", nullable = false, length = 255)
    private String categorySlug;

    // P1-1：列表页一次加载多篇文章时按 IN 批量抓取 tags，消除 1+N 查询
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "post_tags", joinColumns = @JoinColumn(name = "post_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "tag", nullable = false, length = 80)
    private List<String> tags = new ArrayList<>();

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "display_number", nullable = false, length = 10)
    private String number;

    @Column(nullable = false)
    private boolean featured;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PostStatus status = PostStatus.DRAFT;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    // 3A-1：Markdown 化双字段——markdown 正文可空，格式按篇标记
    @Column(name = "markdown_content", columnDefinition = "text")
    private String markdownContent;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_format", nullable = false, length = 16)
    private ContentFormat contentFormat = ContentFormat.HTML;

    @Column(name = "like_count", nullable = false)
    private int likeCount;

    @Column(name = "views_count", nullable = false)
    private int viewsCount;

    protected PostEntity() {
    }

    public static PostEntity create(PostRequest request, String slug, PostContentSanitizer sanitizer) {
        var post = new PostEntity();
        post.update(request, slug, sanitizer);
        return post;
    }

    public void update(PostRequest request, String slug, PostContentSanitizer sanitizer) {
        this.slug = slug;
        this.title = request.title();
        this.excerpt = request.excerpt();
        this.date = request.date();
        this.readTime = request.readTime();
        this.category = request.category();
        this.categorySlug = CategorySlug.fromName(request.category());
        this.tags.clear();
        this.tags.addAll(request.tags());
        this.color = request.color();
        this.number = request.number();
        this.featured = request.featured();
        this.status = request.status();
        // 3A-1：MARKDOWN 篇存原文（渲染在前端受控管线），content 列保留消毒后的 HTML 快照（可为空串）；
        // HTML 篇维持既有写入路径，markdown 列顺带保存（转换工具回填时用）
        this.contentFormat = request.contentFormatOrDefault();
        this.markdownContent = request.markdownContent();
        this.content = sanitizer.sanitize(request.content() == null ? "" : request.content());
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getTitle() { return title; }
    public String getExcerpt() { return excerpt; }
    public LocalDate getDate() { return date; }
    public int getReadTime() { return readTime; }
    public String getCategory() { return category; }
    public String getCategorySlug() { return categorySlug; }
    public List<String> getTags() { return List.copyOf(tags); }
    public String getColor() { return color; }
    public String getNumber() { return number; }
    public boolean isFeatured() { return featured; }
    public PostStatus getStatus() { return status; }
    public String getContent() { return content; }
    public String getMarkdownContent() { return markdownContent; }
    public ContentFormat getContentFormat() { return contentFormat; }
    /** 3A-2：转换工具回填存量——只补 markdown 列与格式标记，不动既有 HTML。 */
    public void applyMarkdownConversion(String markdown, ContentFormat format) {
        this.markdownContent = markdown;
        this.contentFormat = format;
    }
    /** 4C：版本恢复——只回写正文相关字段（快照存的是消毒后落库值），meta 不动。 */
    void applyRevision(String title, String excerpt, String content, String markdownContent, ContentFormat format) {
        this.title = title;
        this.excerpt = excerpt;
        this.content = content;
        this.markdownContent = markdownContent;
        this.contentFormat = format;
    }
    public int getLikeCount() { return likeCount; }
    public void setLikeCount(int likeCount) { this.likeCount = likeCount; }
    public int getViewsCount() { return viewsCount; }
    public void setViewsCount(int viewsCount) { this.viewsCount = viewsCount; }
}
