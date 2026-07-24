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

    protected PostEntity() {
    }

    public static PostEntity create(PostRequest request, PostContentSanitizer sanitizer) {
        var post = new PostEntity();
        post.update(request, sanitizer);
        return post;
    }

    public void update(PostRequest request, PostContentSanitizer sanitizer) {
        this.slug = request.slug();
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
        this.content = sanitizer.sanitize(request.content());
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
}
