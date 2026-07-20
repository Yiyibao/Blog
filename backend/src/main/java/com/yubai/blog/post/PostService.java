package com.yubai.blog.post;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.NotFoundException;

@Service
@Transactional(readOnly = true)
public class PostService {
    private final PostRepository repository;

    public PostService(PostRepository repository) {
        this.repository = repository;
    }

    public List<PostResponse> findAll() {
        return repository.findAllByOrderByDateDesc().stream().map(PostResponse::from).toList();
    }

    public PostResponse findBySlug(String slug) {
        return repository.findBySlug(slug)
            .map(PostResponse::from)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + slug));
    }

    public List<String> findCategories() {
        return repository.findDistinctCategories();
    }
}
