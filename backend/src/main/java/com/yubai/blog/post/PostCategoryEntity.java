package com.yubai.blog.post;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "post_categories")
public class PostCategoryEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 80)
    private String name;

    @Column(nullable = false, unique = true, length = 255)
    private String slug;

    @Column(nullable = false, length = 500)
    private String description = "";

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PostCategoryEntity() {}

    static PostCategoryEntity create(String name, String slug, String description) {
        var category = new PostCategoryEntity();
        category.name = name;
        category.slug = slug;
        category.description = description;
        return category;
    }

    void update(String name, String slug, String description) {
        this.name = name;
        this.slug = slug;
        this.description = description;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getSlug() { return slug; }
    public String getDescription() { return description; }
}
