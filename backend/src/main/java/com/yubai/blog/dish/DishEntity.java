package com.yubai.blog.dish;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import org.hibernate.annotations.BatchSize;

@Entity
@Table(name = "dishes")
public class DishEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 120)
    private String slug;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String summary;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(name = "image_url", nullable = false, columnDefinition = "text")
    private String imageUrl;

    @Column(name = "image_alt", nullable = false, length = 240)
    private String imageAlt;

    @Column(name = "image_credit", nullable = false, length = 240)
    private String imageCredit;

    @Column(name = "image_source_url", nullable = false, columnDefinition = "text")
    private String imageSourceUrl;

    @Column(name = "prep_minutes", nullable = false)
    private int prepMinutes;

    @Column(nullable = false, length = 20)
    private String difficulty;

    @Column(nullable = false, precision = 2, scale = 1)
    private BigDecimal rating;

    @Column(nullable = false)
    private boolean featured;

    @Column(nullable = false)
    private boolean published;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "favorite_count", nullable = false)
    private int favoriteCount;

    @Column(name = "base_servings", nullable = false)
    private int baseServings = 2;

    // 3C：P1-8 真实浏览量模式推广——数据库端原子自增，去重窗口在控制器层
    @Column(name = "views_count", nullable = false)
    private int viewsCount;

    // P1-1：列表页批量抓取食材，消除 1+2N 查询
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dish_ingredients", joinColumns = @JoinColumn(name = "dish_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "ingredient", nullable = false, length = 240)
    private List<String> ingredients = new ArrayList<>();

    // P1-1：列表页批量抓取步骤，消除 1+2N 查询
    @BatchSize(size = 50)
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "dish_steps", joinColumns = @JoinColumn(name = "dish_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "instruction", nullable = false, columnDefinition = "text")
    private List<String> steps = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DishEntity() {
    }

    public static DishEntity create(DishRequest request) {
        var dish = new DishEntity();
        dish.update(request);
        return dish;
    }

    public void update(DishRequest request) {
        this.slug = request.slug();
        this.name = request.name();
        this.summary = request.summary();
        this.category = request.category();
        this.imageUrl = request.imageUrl();
        this.imageAlt = request.imageAlt();
        this.imageCredit = request.imageCredit();
        this.imageSourceUrl = request.imageSourceUrl();
        this.prepMinutes = request.prepMinutes();
        this.difficulty = request.difficulty();
        this.rating = request.rating();
        this.featured = request.featured();
        this.published = request.published();
        this.displayOrder = request.displayOrder();
        this.baseServings = request.baseServings();
        this.ingredients.clear();
        this.ingredients.addAll(request.ingredients());
        this.steps.clear();
        this.steps.addAll(request.steps());
    }

    public void updateImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSlug() { return slug; }
    public String getName() { return name; }
    public String getSummary() { return summary; }
    public String getCategory() { return category; }
    public String getImageUrl() { return imageUrl; }
    public String getImageAlt() { return imageAlt; }
    public String getImageCredit() { return imageCredit; }
    public String getImageSourceUrl() { return imageSourceUrl; }
    public int getPrepMinutes() { return prepMinutes; }
    public String getDifficulty() { return difficulty; }
    public BigDecimal getRating() { return rating; }
    public boolean isFeatured() { return featured; }
    public boolean isPublished() { return published; }
    public int getDisplayOrder() { return displayOrder; }
    public int getFavoriteCount() { return favoriteCount; }
    public int getViewsCount() { return viewsCount; }
    public int getBaseServings() { return baseServings; }
    public List<String> getIngredients() { return Collections.unmodifiableList(ingredients); }
    public List<String> getSteps() { return Collections.unmodifiableList(steps); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

}
