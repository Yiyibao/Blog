package com.yubai.blog.search;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@Service
@Transactional(readOnly = true)
public class SearchService {

    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final NoteRepository noteRepository;

    public SearchService(PostRepository postRepository, DishRepository dishRepository, NoteRepository noteRepository) {
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.noteRepository = noteRepository;
    }

    public SearchResponse search(String query, int requestedLimit) {
        var normalized = query.trim().toLowerCase();
        if (normalized.isBlank()) {
            return SearchResponse.empty();
        }

        var limit = Math.max(1, Math.min(10, requestedLimit));
        var pageable = PageRequest.of(0, limit);
        var likePattern = "%" + normalized + "%";

        var articles = postRepository.searchPublished(likePattern, pageable).stream()
            .map(post -> new SearchResult("POST", post.getId(), post.getTitle(), post.getExcerpt(),
                post.getCategory(), "/articles/" + post.getSlug(), post.getColor(), post.getNumber()))
            .toList();

        var dishes = dishRepository.searchPublished(likePattern, pageable).stream()
            .map(dish -> new SearchResult("DISH", dish.getId(), dish.getName(), dish.getSummary(),
                dish.getCategory(), "/recipes?dish=" + dish.getSlug(), null, null))
            .toList();

        var notes = noteRepository.searchPublished(likePattern, pageable).stream()
            .map(note -> {
                var excerpt = note.getMarkdownContent()
                    .replaceAll("(?m)^#{1,6}\\s+|[#*_~`>\\[\\]()-]", "")
                    .replaceAll("\\s+", " ").trim();
                if (excerpt.length() > 200) {
                    excerpt = excerpt.substring(0, 200).trim() + "...";
                }
                if (excerpt.isBlank()) {
                    excerpt = note.getFolder();
                }
                return new SearchResult("NOTE", note.getId(), note.getTitle(), excerpt,
                    note.getFolder(), "/notes?note=" + note.getId(), null, null);
            })
            .toList();

        return new SearchResponse(articles, notes, dishes, articles.size() + notes.size() + dishes.size());
    }
}
