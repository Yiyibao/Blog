package com.yubai.blog.project;

import java.util.ArrayList;
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
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class ProjectEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 160)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "project_stack", joinColumns = @JoinColumn(name = "project_id"))
    @OrderColumn(name = "sort_order")
    @Column(name = "technology", nullable = false, length = 80)
    private List<String> stack = new ArrayList<>();

    @Column(nullable = false, length = 10)
    private String year;

    @Column(nullable = false, length = 40)
    private String status;

    @Column(nullable = false, length = 20)
    private String color;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    protected ProjectEntity() {
    }

    public String getTitle() { return title; }
    public String getDescription() { return description; }
    public List<String> getStack() { return List.copyOf(stack); }
    public String getYear() { return year; }
    public String getStatus() { return status; }
    public String getColor() { return color; }
    public int getDisplayOrder() { return displayOrder; }
}
