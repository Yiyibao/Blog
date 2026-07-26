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
            .map(SearchService::toResult)
            .toList();

        var dishes = dishRepository.searchPublished(likePattern, pageable).stream()
            .map(SearchService::toResult)
            .toList();

        var notes = noteRepository.searchPublished(likePattern, pageable).stream()
            .map(SearchService::toResult)
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
            allResults.addAll(posts.stream().map(SearchService::toResult).toList());
            allResults.addAll(dishes.stream().map(SearchService::toResult).toList());
            allResults.addAll(notes.stream().map(SearchService::toResult).toList());

            long total = posts.getTotalElements() + dishes.getTotalElements() + notes.getTotalElements();
            return new SearchPostResponse("ALL", request.query(), allResults, 0, allResults.size(), total, 1);
        }

        if (type == SearchType.POST) {
            var page = postRepository.searchPublished(likePattern, pageable);
            return new SearchPostResponse("POST", request.query(),
                page.stream().map(SearchService::toResult).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }

        if (type == SearchType.DISH) {
            var page = dishRepository.searchPublished(likePattern, pageable);
            return new SearchPostResponse("DISH", request.query(),
                page.stream().map(SearchService::toResult).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }

        if (type == SearchType.NOTE) {
            var page = noteRepository.searchPublished(likePattern, pageable);
            return new SearchPostResponse("NOTE", request.query(),
                page.stream().map(SearchService::toResult).toList(),
                page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }

        return SearchPostResponse.empty(request.type().name(), request.query());
    }

    // NB-5：三类命中均由轻量投影行映射，不再为拼摘要/URL 捞整实体（正文列不出库）。

    private static SearchResult toResult(PostRepository.PostSearchRow post) {
        return new SearchResult("POST", post.getId(), post.getTitle(), post.getExcerpt(),
            post.getCategory(), "/articles/" + post.getSlug(), post.getColor(), post.getNumber(), post.getSlug());
    }

    private static SearchResult toResult(DishRepository.DishSearchRow dish) {
        return new SearchResult("DISH", dish.getId(), dish.getName(), dish.getSummary(),
            dish.getCategory(), "/recipes?dish=" + dish.getSlug(), null, null, dish.getSlug());
    }

    private static SearchResult toResult(NoteRepository.NoteSearchRow note) {
        return new SearchResult("NOTE", note.getId(), note.getTitle(), noteExcerpt(note),
            note.getFolder(), "/notes?note=" + note.getId(), null, null, null);
    }

    /** 笔记摘要：由投影截取的前 400 字符正文清洗生成（展示上限 200 字符，400 字符源足够）。 */
    static String noteExcerpt(NoteRepository.NoteSearchRow note) {
        var source = note.getExcerptSource() == null ? "" : note.getExcerptSource();
        var excerpt = source
            .replaceAll("(?m)^#{1,6}\\s+|[#*_~`>\\[\\]()-]", "")
            .replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 200) {
            excerpt = excerpt.substring(0, 200).trim() + "...";
        }
        if (excerpt.isBlank()) {
            excerpt = note.getFolder();
        }
        return excerpt;
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
