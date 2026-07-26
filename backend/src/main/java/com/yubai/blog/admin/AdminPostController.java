package com.yubai.blog.admin;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.post.PostRequest;
import com.yubai.blog.post.PostResponse;
import com.yubai.blog.post.PostService;
import com.yubai.blog.post.PostStatus;
import com.yubai.blog.post.PostSummary;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/v1/admin/posts")
public class AdminPostController {
    private final PostService service;

    public AdminPostController(PostService service) {
        this.service = service;
    }

    /** P1-2：列表只出摘要，编辑时前端经 findOne 拉全文。 */
    @GetMapping
    public ApiResponse<PageResponse<PostSummary>> findAll(
        @RequestParam(required = false) PostStatus status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(service.findAdmin(status, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<PostResponse> findOne(@PathVariable long id) {
        return ApiResponse.ok(service.findOne(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PostResponse> create(@Valid @RequestBody PostRequest request) {
        return ApiResponse.ok(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<PostResponse> update(@PathVariable long id, @Valid @RequestBody PostRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        service.delete(id);
    }
}
