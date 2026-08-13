package com.yubai.blog.kitchen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "shopping_list_items")
public class ShoppingListItemEntity {
    @Id private UUID id;

    @Column(name = "list_id", nullable = false)
    private UUID listId;

    @Column(name = "normalized_name", nullable = false, length = 160)
    private String normalizedName;

    @Column(name = "display_name", nullable = false, length = 160)
    private String displayName;

    @Column(precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(nullable = false, length = 32)
    private String unit;

    @Column(name = "original_quantity", nullable = false, length = 240)
    private String originalQuantity;

    @Column(name = "source_recipe", nullable = false, length = 500)
    private String sourceRecipe;

    @Column(nullable = false, length = 60)
    private String category;

    @Column(nullable = false)
    private boolean checked;

    @Column(nullable = false)
    private boolean manual;

    @Column(nullable = false, length = 240)
    private String note;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShoppingListItemEntity() {}

    private ShoppingListItemEntity(
            UUID listId,
            String normalizedName,
            String displayName,
            BigDecimal quantity,
            String unit,
            String originalQuantity,
            String sourceRecipe,
            String category,
            boolean checked,
            boolean manual,
            String note,
            int sortOrder) {
        this.id = UUID.randomUUID();
        this.listId = listId;
        this.update(
                normalizedName,
                displayName,
                quantity,
                unit,
                originalQuantity,
                sourceRecipe,
                category,
                checked,
                manual,
                note,
                sortOrder);
        this.createdAt = Instant.now();
    }

    public static ShoppingListItemEntity create(
            UUID listId,
            String normalizedName,
            String displayName,
            BigDecimal quantity,
            String unit,
            String originalQuantity,
            String sourceRecipe,
            String category,
            boolean checked,
            boolean manual,
            String note,
            int sortOrder) {
        return new ShoppingListItemEntity(
                listId,
                normalizedName,
                displayName,
                quantity,
                unit,
                originalQuantity,
                sourceRecipe,
                category,
                checked,
                manual,
                note,
                sortOrder);
    }

    public void update(
            String normalizedName,
            String displayName,
            BigDecimal quantity,
            String unit,
            String originalQuantity,
            String sourceRecipe,
            String category,
            boolean checked,
            boolean manual,
            String note,
            int sortOrder) {
        this.normalizedName = normalizedName;
        this.displayName = displayName;
        this.quantity = quantity;
        this.unit = unit;
        this.originalQuantity = originalQuantity;
        this.sourceRecipe = sourceRecipe;
        this.category = category;
        this.checked = checked;
        this.manual = manual;
        this.note = note;
        this.sortOrder = sortOrder;
    }

    public UUID getId() {
        return id;
    }

    public UUID getListId() {
        return listId;
    }

    public String getNormalizedName() {
        return normalizedName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public BigDecimal getQuantity() {
        return quantity;
    }

    public String getUnit() {
        return unit;
    }

    public String getOriginalQuantity() {
        return originalQuantity;
    }

    public String getSourceRecipe() {
        return sourceRecipe;
    }

    public String getCategory() {
        return category;
    }

    public boolean isChecked() {
        return checked;
    }

    public boolean isManual() {
        return manual;
    }

    public String getNote() {
        return note;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
