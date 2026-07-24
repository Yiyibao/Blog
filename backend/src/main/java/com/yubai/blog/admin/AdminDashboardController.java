package com.yubai.blog.admin;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@RestController
@RequestMapping("/api/v1/admin/stats")
public class AdminDashboardController {

    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final NoteRepository noteRepository;

    public AdminDashboardController(PostRepository postRepository, DishRepository dishRepository, NoteRepository noteRepository) {
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.noteRepository = noteRepository;
    }

    @GetMapping
    public ApiResponse<Stats> getStats() {
        return ApiResponse.ok(new Stats(
            postRepository.count(),
            dishRepository.count(),
            noteRepository.count()
        ));
    }

    public record Stats(long posts, long dishes, long notes) {}
}
