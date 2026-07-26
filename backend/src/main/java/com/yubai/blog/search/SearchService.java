package com.yubai.blog.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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

    /** L-16/D-17：includeNotes=false（游客）时笔记命中整体剔除。 */
    public SearchResponse search(String query, int requestedLimit, boolean includeNotes) {
        var normalized = query.trim().toLowerCase();
        if (normalized.isBlank()) {
            return SearchResponse.empty();
        }

        var limit = Math.max(1, Math.min(10, requestedLimit));
        var pageable = PageRequest.of(0, limit);
        var likePattern = "%" + escapeLike(normalized) + "%";

        var articles = postRepository.searchPublished(likePattern, null, withDateSort(pageable, SearchSort.DATE_DESC)).stream()
            .map(SearchService::toResult)
            .toList();

        var dishes = dishRepository.searchPublished(likePattern, pageable).stream()
            .map(SearchService::toResult)
            .toList();

        var notes = includeNotes
            ? noteRepository.searchPublished(likePattern, pageable).stream()
                .map(SearchService::toResult)
                .toList()
            : List.<SearchResult>of();

        return new SearchResponse(articles, notes, dishes, articles.size() + notes.size() + dishes.size());
    }

    public SearchPostResponse search(SearchRequest request, boolean includeNotes) {
        var normalized = request.query().trim().toLowerCase();
        if (normalized.isBlank()) {
            return SearchPostResponse.empty(request.type().name(), request.query());
        }

        var pageable = PageRequests.of(request.page(), request.size());
        var likePattern = "%" + escapeLike(normalized) + "%";
        var type = request.type();

        // L-16/D-17：游客的 NOTE 类型检索直接空页（枚举合法故不 400，与"不可见"语义一致）
        if (type == SearchType.NOTE && !includeNotes) {
            return SearchPostResponse.empty("NOTE", request.query());
        }

        if (type == SearchType.ALL) {
            var maxSize = Math.max(1, Math.min(10, request.size()));
            var allPageable = PageRequest.of(0, maxSize);
            var posts = postRepository.searchPublished(likePattern, null, withDateSort(allPageable, SearchSort.DATE_DESC));
            var dishes = dishRepository.searchPublished(likePattern, allPageable);

            List<SearchResult> allResults = new ArrayList<>();
            allResults.addAll(posts.stream().map(SearchService::toResult).toList());
            allResults.addAll(dishes.stream().map(SearchService::toResult).toList());

            long total = posts.getTotalElements() + dishes.getTotalElements();
            if (includeNotes) {
                var notes = noteRepository.searchPublished(likePattern, allPageable);
                allResults.addAll(notes.stream().map(SearchService::toResult).toList());
                total += notes.getTotalElements();
            }
            return new SearchPostResponse("ALL", request.query(), allResults, 0, allResults.size(), total, 1);
        }

        if (type == SearchType.POST) {
            // L-8：分类过滤与排序下推到数据库，命中补 date/readTime/tags——前端不再客户端补偿
            var page = postRepository.searchPublished(likePattern, request.categorySlugOrNull(),
                withDateSort(pageable, request.sortOrDefault()));
            var tagsByPost = tagsFor(page.getContent());
            return new SearchPostResponse("POST", request.query(),
                page.stream().map(row -> toResult(row, tagsByPost.getOrDefault(row.getId(), List.of()))).toList(),
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

    /** L-8：排序统一由 Pageable.Sort 表达（仓库查询不再内嵌 ORDER BY）。 */
    private static PageRequest withDateSort(org.springframework.data.domain.Pageable pageable, SearchSort sort) {
        var direction = sort == SearchSort.DATE_ASC ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(direction, "date"));
    }

    /** L-8：一页命中批量补标签（复用 L-12 的 [postId, tag] 行查询，一次 IN）。 */
    private Map<Long, List<String>> tagsFor(List<PostRepository.PostSearchRow> rows) {
        if (rows.isEmpty()) return Map.of();
        var ids = rows.stream().map(PostRepository.PostSearchRow::getId).toList();
        return postRepository.findTagRows(ids).stream().collect(Collectors.groupingBy(
            row -> (Long) row[0],
            Collectors.mapping(row -> (String) row[1], Collectors.toList())));
    }

    // NB-5：三类命中均由轻量投影行映射，不再为拼摘要/URL 捞整实体（正文列不出库）。

    private static SearchResult toResult(PostRepository.PostSearchRow post) {
        return new SearchResult("POST", post.getId(), post.getTitle(), post.getExcerpt(),
            post.getCategory(), "/articles/" + post.getSlug(), post.getColor(), post.getNumber(), post.getSlug(),
            null, null, null);
    }

    /** L-8：POST 分页分支的完整命中——含文章头所需 date/readTime/tags。 */
    private static SearchResult toResult(PostRepository.PostSearchRow post, List<String> tags) {
        return new SearchResult("POST", post.getId(), post.getTitle(), post.getExcerpt(),
            post.getCategory(), "/articles/" + post.getSlug(), post.getColor(), post.getNumber(), post.getSlug(),
            post.getDate().toString(), post.getReadTime(), tags);
    }

    private static SearchResult toResult(DishRepository.DishSearchRow dish) {
        return new SearchResult("DISH", dish.getId(), dish.getName(), dish.getSummary(),
            dish.getCategory(), "/recipes?dish=" + dish.getSlug(), null, null, dish.getSlug(),
            null, null, null);
    }

    private static SearchResult toResult(NoteRepository.NoteSearchRow note) {
        return new SearchResult("NOTE", note.getId(), note.getTitle(), noteExcerpt(note),
            note.getFolder(), "/notes?note=" + note.getId(), null, null, null,
            null, null, null);
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
