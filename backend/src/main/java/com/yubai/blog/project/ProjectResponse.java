package com.yubai.blog.project;

import java.util.List;

public record ProjectResponse(
    String title,
    String description,
    List<String> stack,
    String year,
    String status,
    String color
) {
    static ProjectResponse from(ProjectEntity project) {
        return new ProjectResponse(
            project.getTitle(), project.getDescription(), project.getStack(),
            project.getYear(), project.getStatus(), project.getColor()
        );
    }
}
