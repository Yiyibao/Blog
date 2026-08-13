package com.yubai.blog.kitchen;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(
        name = "shopping_lists",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uk_shopping_lists_owner_week",
                        columnNames = {"owner_id", "week_start"}))
public class ShoppingListEntity {
    @Id private UUID id;

    @Column(name = "owner_id", nullable = false)
    private long ownerId;

    @Column(name = "week_start", nullable = false)
    private LocalDate weekStart;

    @Column(nullable = false, length = 500)
    private String note;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "last_mutation_key", length = 160)
    private String lastMutationKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ShoppingListEntity() {}

    private ShoppingListEntity(long ownerId, LocalDate weekStart) {
        this.id = UUID.randomUUID();
        this.ownerId = ownerId;
        this.weekStart = weekStart;
        this.note = "";
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static ShoppingListEntity create(long ownerId, LocalDate weekStart) {
        return new ShoppingListEntity(ownerId, weekStart);
    }

    public void updateNote(String note) {
        this.note = note == null ? "" : note;
        this.updatedAt = Instant.now();
    }

    public void markMutation(String mutationKey) {
        this.lastMutationKey = mutationKey;
        this.updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public long getOwnerId() {
        return ownerId;
    }

    public LocalDate getWeekStart() {
        return weekStart;
    }

    public String getNote() {
        return note;
    }

    public long getVersion() {
        return version;
    }

    public String getLastMutationKey() {
        return lastMutationKey;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
