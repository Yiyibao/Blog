package com.yubai.blog.project;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;

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

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        return ProjectResponse.from(repository.save(ProjectEntity.create(request)));
    }

    @Transactional
    public ProjectResponse update(long id, ProjectRequest request) {
        var project = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("项目不存在：" + id));
        project.update(request);
        return ProjectResponse.from(project);
    }

    @Transactional
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("项目不存在：" + id);
        }
        repository.deleteById(id);
    }
}
