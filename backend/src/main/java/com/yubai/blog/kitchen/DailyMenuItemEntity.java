package com.yubai.blog.kitchen;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** FD-10：菜单里的一道菜。title 是写入时的快照；author_* 为署名，PUT diff 时不得改写。 */
@Entity
@Table(name = "daily_menu_items")
public class DailyMenuItemEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_id", nullable = false)
    private long menuId;

    @Column(name = "dish_id")
    private Long dishId;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "meal_slot", nullable = false, length = 16)
    private MealSlot mealSlot;

    @Column(nullable = false, length = 200)
    private String note;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "author_id", nullable = false)
    private long authorId;

    @Column(name = "author_name", nullable = false, length = 40)
    private String authorName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected DailyMenuItemEntity() {
    }

    private DailyMenuItemEntity(long menuId, Long dishId, String title, MealSlot mealSlot,
                                String note, int sortOrder, long authorId, String authorName) {
        this.menuId = menuId;
        this.dishId = dishId;
        this.title = title;
        this.mealSlot = mealSlot;
        this.note = note;
        this.sortOrder = sortOrder;
        this.authorId = authorId;
        this.authorName = authorName;
        this.createdAt = Instant.now();
    }

    public static DailyMenuItemEntity create(long menuId, Long dishId, String title, MealSlot mealSlot,
                                             String note, int sortOrder, long authorId, String authorName) {
        return new DailyMenuItemEntity(menuId, dishId, title, mealSlot, note, sortOrder, authorId, authorName);
    }

    /** PUT diff 对既有项只更新可变字段——author_id/author_name/created_at 永不触碰。 */
    public void updateMutable(String title, MealSlot mealSlot, String note, int sortOrder) {
        this.title = title;
        this.mealSlot = mealSlot;
        this.note = note;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public long getMenuId() { return menuId; }
    public Long getDishId() { return dishId; }
    public String getTitle() { return title; }
    public MealSlot getMealSlot() { return mealSlot; }
    public String getNote() { return note; }
    public int getSortOrder() { return sortOrder; }
    public long getAuthorId() { return authorId; }
    public String getAuthorName() { return authorName; }
    public Instant getCreatedAt() { return createdAt; }
}
