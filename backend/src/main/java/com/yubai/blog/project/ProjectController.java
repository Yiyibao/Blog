package com.yubai.blog.project;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {
    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> findAll() {
        return ApiResponse.ok(service.findAll());
    }
}
