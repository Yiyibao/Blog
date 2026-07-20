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

    @Transactional
    public PostResponse create(PostRequest request) {
        return PostResponse.from(repository.save(PostEntity.create(request)));
    }

    @Transactional
    public PostResponse update(long id, PostRequest request) {
        var post = repository.findById(id)
            .orElseThrow(() -> new NotFoundException("文章不存在：" + id));
        post.update(request);
        return PostResponse.from(post);
    }

    @Transactional
    public void delete(long id) {
        if (!repository.existsById(id)) {
            throw new NotFoundException("文章不存在：" + id);
        }
        repository.deleteById(id);
    }
}
