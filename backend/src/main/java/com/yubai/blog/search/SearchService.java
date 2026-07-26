package com.yubai.blog.search;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.common.PageRequests;
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
        var likePattern = "%" + escapeLike(normalized) + "%";

        var articles = postRepository.searchPublished(likePattern, pageable).stream()
            .map(post -> new SearchResult("POST", post.getId(), post.getTitle(), post.getExcerpt(),
                post.getCategory(), "/articles/" + post.getSlug(), post.getColor(), post.getNumber(), post.getSlug()))
            .toList();

        var dishes = dishRepository.searchPublished(likePattern, pageable).stream()
            .map(dish -> new SearchResult("DISH", dish.getId(), dish.getName(), dish.getSummary(),
                dish.getCategory(), "/recipes?dish=" + dish.getSlug(), null, null, dish.getSlug()))
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
                    note.getFolder(), "/notes?note=" + note.getId(), null, null, null);
            })
            .toList();

        return new SearchResponse(articles, notes, dishes, articles.size() + notes.size() + dishes.size());
    }

    public SearchPostResponse search(SearchRequest request) {
        var normalized = request.query().trim().toLowerCase();
        if (normalized.isBlank()) {
            return SearchPostResponse.empty(request.type().name(), request.query());
        }

        var pageable = PageRequests.of(request.page(), request.size());
        var likePattern = "%" + escapeLike(normalized) + "%";
        var type = request.type();

        if (type == SearchType.ALL) {
            var maxSize = Math.max(1, Math.min(10, request.size()));
            var allPageable = PageRequest.of(0, maxSize);
            var posts = postRepository.searchPublished(likePattern, allPageable);
            var dishes = dishRepository.searchPublished(likePattern, allPageable);
            var notes = noteRepository.searchPublished(likePattern, allPageable);

            List<SearchResult> allResults = new ArrayList<>();
            allResults.addAll(posts.stream()
                .map(p -> new SearchResult("POST", p.getId(), p.getTitle(), p.getExcerpt(),
                    p.getCategory(), "/articles/" + p.getSlug(), p.getColor(), p.getNumber(), p.getSlug()))
                .toList());
            allResults.addAll(dishes.stream()
                .map(d -> new SearchResult("DISH", d.getId(), d.getName(), d.getSummary(),
                    d.getCategory(), "/recipes?dish=" + d.getSlug(), null, null, d.getSlug()))
                .toList());
            allResults.addAll(notes.stream()
                .map(n -> {
                    var excerpt = n.getMarkdownContent()
                        .replaceAll("(?m)^#{1,6}\\s+|[#*_~`>\\[\\]()-]", "")
                        .replaceAll("\\s+", " ").trim();
                    if (excerpt.length() > 200) {
                        excerpt = excerpt.substring(0, 200).trim() + "...";
                    }
                    if (excerpt.isBlank()) {
                        excerpt = n.getFolder();
                    }
                    return new SearchResult("NOTE", n.getId(), n.getTitle(), excerpt,
                        n.getFolder(), "/notes?note=" + n.getId(), null, null, null);
                })
                .toList());

            long total = posts.getTotalElements() + dishes.getTotalElements() + notes.getTotalElements();
            return new SearchPostResponse("ALL", request.query(), allResults, 0, allResults.size(), total, 1);
        }

        if (type == SearchType.POST) {
            var page = postRepository.searchPublished(likePattern, pageable);
            var results = page.stream()
                .map(p -> new SearchResult("POST", p.getId(), p.getTitle(), p.getExcerpt(),
                    p.getCategory(), "/articles/" + p.getSlug(), p.getColor(), p.getNumber(), p.getSlug()))
                .toList();
            return new SearchPostResponse("POST", request.query(), results,
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }

        if (type == SearchType.DISH) {
            var page = dishRepository.searchPublished(likePattern, pageable);
            var results = page.stream()
                .map(d -> new SearchResult("DISH", d.getId(), d.getName(), d.getSummary(),
                    d.getCategory(), "/recipes?dish=" + d.getSlug(), null, null, d.getSlug()))
                .toList();
            return new SearchPostResponse("DISH", request.query(), results,
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }

        if (type == SearchType.NOTE) {
            var page = noteRepository.searchPublished(likePattern, pageable);
            var results = page.stream()
                .map(n -> {
                    var excerpt = n.getMarkdownContent()
                        .replaceAll("(?m)^#{1,6}\\s+|[#*_~`>\\[\\]()-]", "")
                        .replaceAll("\\s+", " ").trim();
                    if (excerpt.length() > 200) {
                        excerpt = excerpt.substring(0, 200).trim() + "...";
                    }
                    if (excerpt.isBlank()) {
                        excerpt = n.getFolder();
                    }
                    return new SearchResult("NOTE", n.getId(), n.getTitle(), excerpt,
                        n.getFolder(), "/notes?note=" + n.getId(), null, null, null);
                })
                .toList();
            return new SearchPostResponse("NOTE", request.query(), results,
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }

        return SearchPostResponse.empty(request.type().name(), request.query());
    }

    /**
     * P0-9：转义 LIKE 通配符，防止 %/_/\ 注入模式匹配。
     * PostgreSQL 中 LIKE 的默认转义符即反斜杠。
     */
    static String escapeLike(String input) {
        return input
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
    }
}
