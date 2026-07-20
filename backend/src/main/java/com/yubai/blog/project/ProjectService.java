package com.yubai.blog.project;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class ProjectService {
    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<ProjectResponse> findAll() {
        return repository.findAllByOrderByDisplayOrderAsc().stream().map(ProjectResponse::from).toList();
    }
}
