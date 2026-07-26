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
import jakarta.persistence.Version;

/**
 * FD-10：某一天的菜单头。子项刻意不做 JPA 关联（独立仓库显式查询），
 * 一来避开 @ElementCollection/@OneToMany 的批量抓取阈值（ListQueryBatchingTest ≤3 prepare），
 * 二来让"append 不动父实体版本"的语义清晰可控。
 */
@Entity
@Table(name = "daily_menus")
public class DailyMenuEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_date", nullable = false, unique = true)
    private LocalDate menuDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MenuStatus status;

    @Column(nullable = false, length = 500)
    private String note;

    @Column(name = "created_by", nullable = false)
    private long createdBy;

    @Column(name = "updated_by", nullable = false)
    private long updatedBy;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DailyMenuEntity() {
    }

    private DailyMenuEntity(LocalDate menuDate, long creatorId) {
        this.menuDate = menuDate;
        this.status = MenuStatus.DRAFT;
        this.note = "";
        this.createdBy = creatorId;
        this.updatedBy = creatorId;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static DailyMenuEntity create(LocalDate menuDate, long creatorId) {
        return new DailyMenuEntity(menuDate, creatorId);
    }

    public void update(MenuStatus status, String note, long editorId) {
        this.status = status;
        this.note = note;
        this.updatedBy = editorId;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public LocalDate getMenuDate() { return menuDate; }
    public MenuStatus getStatus() { return status; }
    public String getNote() { return note; }
    public long getCreatedBy() { return createdBy; }
    public long getUpdatedBy() { return updatedBy; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
