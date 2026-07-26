package com.yubai.blog.kitchen;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** FD-15：一次"我们吃过"——log_date 是实际吃的日期（可补记）；title 快照与署名同菜单项语义。 */
@Entity
@Table(name = "meal_logs")
public class MealLogEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "log_date", nullable = false)
    private LocalDate logDate;

    @Column(name = "dish_id")
    private Long dishId;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_slot", nullable = false, length = 16)
    private MealSlot mealSlot;

    @Column
    private Integer rating;

    @Column(nullable = false, length = 300)
    private String note;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(name = "author_name", nullable = false, length = 40)
    private String authorName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MealLogEntity() {
    }

    private MealLogEntity(LocalDate logDate, Long dishId, String title, MealSlot mealSlot,
                          Integer rating, String note, long authorId, String authorName) {
        this.logDate = logDate;
        this.dishId = dishId;
        this.title = title;
        this.mealSlot = mealSlot;
        this.rating = rating;
        this.note = note;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static MealLogEntity create(LocalDate logDate, Long dishId, String title, MealSlot mealSlot,
                                       Integer rating, String note, long authorId, String authorName) {
        return new MealLogEntity(logDate, dishId, title, mealSlot, rating, note, authorId, authorName);
    }

    public Long getId() { return id; }
    public LocalDate getLogDate() { return logDate; }
    public Long getDishId() { return dishId; }
    public String getTitle() { return title; }
    public MealSlot getMealSlot() { return mealSlot; }
    public Integer getRating() { return rating; }
    public String getNote() { return note; }
    public long getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public Instant getCreatedAt() { return createdAt; }
}
