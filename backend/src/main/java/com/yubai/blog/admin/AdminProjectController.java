package com.yubai.blog.admin;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.project.ProjectRequest;
import com.yubai.blog.project.ProjectResponse;
import com.yubai.blog.project.ProjectService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/projects")
public class AdminProjectController {
    private final ProjectService service;

    public AdminProjectController(ProjectService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> findAll() {
        return ApiResponse.ok(service.findAll());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProjectResponse> create(@Valid @RequestBody ProjectRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProjectResponse> update(@PathVariable long id, @Valid @RequestBody ProjectRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
