package com.yubai.blog.stats;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 4D：按日聚合的全站浏览量（文章/菜谱/笔记详情读共同累加）。 */
@Entity
@Table(name = "view_daily")
public class ViewDailyEntity {

    @Id
    private LocalDate day;

    @Column(nullable = false)
    private long views;

    protected ViewDailyEntity() {
    }

    public LocalDate getDay() { return day; }
    public long getViews() { return views; }
}
