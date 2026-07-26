package com.yubai.blog.series;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.CacheConfig;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.post.PostStatus;
import com.yubai.blog.series.SeriesDtos.AdminSeriesResponse;
import com.yubai.blog.series.SeriesDtos.PublicSeriesDetail;
import com.yubai.blog.series.SeriesDtos.PublicSeriesSummary;
import com.yubai.blog.series.SeriesDtos.SeriesEntriesRequest;
import com.yubai.blog.series.SeriesDtos.SeriesEntryItem;
import com.yubai.blog.series.SeriesDtos.SeriesRef;
import com.yubai.blog.series.SeriesDtos.SeriesRequest;

/**
 * 4B：合集编排——建合集 → 挂文章 → 按序阅读全链路。
 * 成员经独立仓库显式查询（无 JPA 关联），文章引用走轻量投影不读正文列；
 * 排序采用整表重排落库（前端拖拽提交完整有序列表），简单且无并发洞。
 */
@Service
@Transactional(readOnly = true)
public class SeriesService {

    private final SeriesRepository seriesRepository;
    private final SeriesEntryRepository entryRepository;
    private final PostRepository postRepository;

    public SeriesService(SeriesRepository seriesRepository, SeriesEntryRepository entryRepository,
                         PostRepository postRepository) {
        this.seriesRepository = seriesRepository;
        this.entryRepository = entryRepository;
        this.postRepository = postRepository;
    }

    // ── 管理端 ──────────────────────────────────────────────────────────────

    public List<AdminSeriesResponse> findAdmin() {
        var all = seriesRepository.findAllByOrderByUpdatedAtDesc();
        if (all.isEmpty()) return List.of();
        var entries = entryRepository.findAllBySeriesIdInOrderBySortOrderAscIdAsc(
            all.stream().map(SeriesEntity::getId).toList());
        var bySeries = entries.stream().collect(Collectors.groupingBy(SeriesEntryEntity::getSeriesId));
        var refs = postRefs(entries);
        return all.stream()
            .map(series -> toAdmin(series, bySeries.getOrDefault(series.getId(), List.of()), refs))
            .toList();
    }

    public AdminSeriesResponse findAdminOne(long id) {
        var series = entity(id);
        var entries = entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(id);
        return toAdmin(series, entries, postRefs(entries));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public AdminSeriesResponse create(SeriesRequest request) {
        if (seriesRepository.existsBySlug(request.slug())) {
            throw new DataIntegrityViolationException("合集 slug 已存在");
        }
        var series = seriesRepository.save(SeriesEntity.create(request));
        return toAdmin(series, List.of(), Map.of());
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public AdminSeriesResponse update(long id, long version, SeriesRequest request) {
        var series = entity(id);
        if (series.getVersion() != version) throw new SeriesVersionConflictException();
        if (seriesRepository.existsBySlugAndIdNot(request.slug(), id)) {
            throw new DataIntegrityViolationException("合集 slug 已存在");
        }
        series.update(request);
        var entries = entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(id);
        return toAdmin(series, entries, postRefs(entries));
    }

    /** 拖拽排序/增删成员的落库形态：整表替换（列表即最终真相）。 */
    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public AdminSeriesResponse setEntries(long id, SeriesEntriesRequest request) {
        var series = entity(id);
        if (series.getVersion() != request.version()) throw new SeriesVersionConflictException();

        var postIds = new LinkedHashSet<Long>();
        for (var input : request.entries()) {
            if (!postIds.add(input.postId())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一篇文章不能重复加入合集");
            }
        }
        var known = postRepository.findRefRows(postIds.isEmpty() ? List.of(-1L) : postIds).stream()
            .map(PostRepository.PostRefRow::getId).collect(Collectors.toSet());
        for (var postId : postIds) {
            if (!known.contains(postId)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文章不存在：" + postId);
            }
        }

        entryRepository.deleteAllBySeriesId(id);
        entryRepository.flush();
        var rows = new ArrayList<SeriesEntryEntity>();
        int order = 0;
        for (var input : request.entries()) {
            rows.add(SeriesEntryEntity.post(id, input.postId(), order++, input.chapterTitle()));
        }
        entryRepository.saveAll(rows);
        series.touch(); // 成员变化也是一次合集编辑：推进乐观锁版本
        return toAdmin(series, rows, postRefs(rows));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public void delete(long id) {
        if (!seriesRepository.existsById(id)) throw new NotFoundException("合集不存在：" + id);
        entryRepository.deleteAllBySeriesId(id);
        seriesRepository.deleteById(id);
    }

    /** 4B 删除钩子：文章删除时清掉所有合集引用（由 PostService.delete 调用）。 */
    @Transactional
    public void removeEntriesForPost(long postId) {
        entryRepository.deleteAllByContent(SeriesEntryEntity.TYPE_POST, postId);
    }

    // ── 公开端 ──────────────────────────────────────────────────────────────

    public List<PublicSeriesSummary> findPublished() {
        var published = seriesRepository.findAllByStatusOrderByPublishedAtDesc(SeriesStatus.PUBLISHED);
        return published.stream()
            .map(series -> new PublicSeriesSummary(series.getSlug(), series.getName(), series.getDescription(),
                series.getCoverImage(), (int) entryRepository.countBySeriesId(series.getId()), series.getPublishedAt()))
            .toList();
    }

    public PublicSeriesDetail findPublishedBySlug(String slug) {
        var series = seriesRepository.findBySlugAndStatus(slug, SeriesStatus.PUBLISHED)
            .orElseThrow(() -> new NotFoundException("合集不存在：" + slug));
        var entries = entryRepository.findAllBySeriesIdOrderBySortOrderAscIdAsc(series.getId());
        var refs = postRefs(entries);
        // 公开视图剔除未发布成员，但位次仍按合集内顺序连续编号
        var items = new ArrayList<SeriesEntryItem>();
        for (var entry : entries) {
            var ref = refs.get(entry.getContentId());
            if (ref == null || ref.getStatus() != PostStatus.PUBLISHED) continue;
            items.add(new SeriesEntryItem(entry.getContentId(), ref.getSlug(), ref.getTitle(),
                ref.getDate().toString(), entry.getChapterTitle(), items.size() + 1));
        }
        return new PublicSeriesDetail(series.getSlug(), series.getName(), series.getDescription(),
            series.getCoverImage(), series.getPublishedAt(), List.copyOf(items));
    }

    /** 文章详情「本文属于合集 X（n/N）」；不属于任何已发布合集时为 null（取第一个命中的合集）。 */
    public SeriesRef seriesRefForPost(long postId) {
        var memberships = entryRepository.findAllByContentTypeAndContentId(SeriesEntryEntity.TYPE_POST, postId);
        for (var membership : memberships) {
            var series = seriesRepository.findById(membership.getSeriesId()).orElse(null);
            if (series == null || series.getStatus() != SeriesStatus.PUBLISHED) continue;
            var detail = findPublishedBySlug(series.getSlug());
            for (var item : detail.entries()) {
                if (item.postId() == postId) {
                    return new SeriesRef(series.getSlug(), series.getName(), item.position(), detail.entries().size());
                }
            }
        }
        return null;
    }

    /** 图谱 SERIES 节点数据：已发布合集及其已发布成员的 postId 列表。 */
    public Map<SeriesEntity, List<Long>> publishedGraphMembers() {
        var published = seriesRepository.findAllByStatusOrderByPublishedAtDesc(SeriesStatus.PUBLISHED);
        if (published.isEmpty()) return Map.of();
        var entries = entryRepository.findAllBySeriesIdInOrderBySortOrderAscIdAsc(
            published.stream().map(SeriesEntity::getId).toList());
        var refs = postRefs(entries);
        var bySeries = entries.stream().collect(Collectors.groupingBy(SeriesEntryEntity::getSeriesId));
        var result = new java.util.LinkedHashMap<SeriesEntity, List<Long>>();
        for (var series : published) {
            var memberIds = bySeries.getOrDefault(series.getId(), List.of()).stream()
                .map(SeriesEntryEntity::getContentId)
                .filter(id -> {
                    var ref = refs.get(id);
                    return ref != null && ref.getStatus() == PostStatus.PUBLISHED;
                })
                .toList();
            result.put(series, memberIds);
        }
        return result;
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    private Map<Long, PostRepository.PostRefRow> postRefs(List<SeriesEntryEntity> entries) {
        var ids = entries.stream().map(SeriesEntryEntity::getContentId).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        return postRepository.findRefRows(ids).stream()
            .collect(Collectors.toMap(PostRepository.PostRefRow::getId, row -> row));
    }

    private AdminSeriesResponse toAdmin(SeriesEntity series, List<SeriesEntryEntity> entries,
                                        Map<Long, PostRepository.PostRefRow> refs) {
        var items = new ArrayList<SeriesEntryItem>();
        for (var entry : entries) {
            var ref = refs.get(entry.getContentId());
            items.add(new SeriesEntryItem(entry.getContentId(),
                ref == null ? "" : ref.getSlug(),
                ref == null ? "（文章已删除）" : ref.getTitle(),
                ref == null ? "" : ref.getDate().toString(),
                entry.getChapterTitle(), items.size() + 1));
        }
        return new AdminSeriesResponse(series.getId(), series.getName(), series.getSlug(), series.getDescription(),
            series.getCoverImage(), series.getStatus(), series.getVersion(), items.size(),
            series.getCreatedAt(), series.getUpdatedAt(), series.getPublishedAt(), List.copyOf(items));
    }

    private SeriesEntity entity(long id) {
        return seriesRepository.findById(id).orElseThrow(() -> new NotFoundException("合集不存在：" + id));
    }
}
