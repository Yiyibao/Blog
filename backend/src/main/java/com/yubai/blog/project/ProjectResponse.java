package com.yubai.blog.project;

import java.util.List;

public record ProjectResponse(
    Long id,
    String title,
    String description,
    List<String> stack,
    String year,
    String status,
    String color,
    int displayOrder
) {
    static ProjectResponse from(ProjectEntity project) {
        return new ProjectResponse(
            project.getId(), project.getTitle(), project.getDescription(), project.getStack(),
            project.getYear(), project.getStatus(), project.getColor(), project.getDisplayOrder()
        );
    }
}
