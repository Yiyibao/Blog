package com.yubai.blog.note;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.PageRequests;
import com.yubai.blog.config.CacheConfig;

@Service
@Transactional(readOnly = true)
public class NoteService {
    private static final int MAX_IMPORT_BYTES = 2_000_000;
    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private final NoteRepository repository;

    public NoteService(NoteRepository repository) { this.repository = repository; }

    /** P1-2：列表只出摘要（不含正文），正文由 findOne / findPublishedOne 返回。 */
    public PageResponse<NoteSummary> findAll(NoteStatus status, int page, int size) {
        var pageable = PageRequests.of(page, size);
        var result = status == null
            ? repository.findAllByOrderByUpdatedAtDesc(pageable)
            : repository.findAllByStatusOrderByUpdatedAtDesc(status, pageable);
        return toSummaryPage(result);
    }

    /** P1-2：公开列表只出摘要（不含正文）。 */
    public PageResponse<NoteSummary> findPublished(int page, int size) {
        return toSummaryPage(repository.findAllByStatusOrderByUpdatedAtDesc(
            NoteStatus.PUBLISHED, PageRequests.of(page, size)));
    }

    /** L-12：投影分页行 + 一次 IN 批量补标签，列表路径全程不读正文列。 */
    private PageResponse<NoteSummary> toSummaryPage(Page<NoteRepository.NoteListRow> page) {
        var ids = page.stream().map(NoteRepository.NoteListRow::getId).toList();
        Map<Long, List<String>> tags = ids.isEmpty() ? Map.of() : repository.findTagRows(ids).stream()
            .collect(Collectors.groupingBy(row -> (Long) row[0],
                Collectors.mapping(row -> (String) row[1], Collectors.toList())));
        return PageResponse.from(page.map(row -> NoteSummary.of(row, tags.getOrDefault(row.getId(), List.of()))));
    }

    public NoteResponse findOne(long id) { return NoteResponse.from(entity(id)); }

    public NoteResponse findPublishedOne(long id) {
        var note = entity(id);
        if (note.getStatus() != NoteStatus.PUBLISHED) throw new NotFoundException("笔记不存在：" + id);
        return NoteResponse.from(note);
    }

    /** 3C：详情读带来的真实浏览计数；未命中（不存在/未发布）静默为 0，不影响详情读取流程。 */
    @org.springframework.transaction.annotation.Transactional
    public int registerView(long id) {
        return repository.incrementViewsCount(id);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public NoteResponse create(NoteRequest request) { return NoteResponse.from(repository.saveAndFlush(NoteEntity.create(request))); }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public NoteResponse update(long id, NoteRequest request) {
        var note = entity(id);
        if (note.getVersion() != request.version()) throw new NoteVersionConflictException();
        note.update(request);
        return NoteResponse.from(repository.saveAndFlush(note));
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public NoteResponse publish(long id, long version) {
        return changeStatus(id, version, NoteStatus.PUBLISHED);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public NoteResponse unpublish(long id, long version) {
        return changeStatus(id, version, NoteStatus.DRAFT);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public NoteResponse archive(long id, long version) {
        return changeStatus(id, version, NoteStatus.ARCHIVED);
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public NoteResponse importMarkdown(MultipartFile file) {
        var filename = file.getOriginalFilename() == null ? "imported-note.md" : file.getOriginalFilename();
        var normalized = filename.toLowerCase(Locale.ROOT);
        if (!(normalized.endsWith(".md") || normalized.endsWith(".markdown") || normalized.endsWith(".txt"))) {
            throw new InvalidNoteFileException("只支持 .md、.markdown 或 .txt 文件");
        }
        if (file.isEmpty() || file.getSize() > MAX_IMPORT_BYTES) throw new InvalidNoteFileException("文件不能为空且不能超过 2 MB");
        try {
            var markdown = new String(file.getBytes(), StandardCharsets.UTF_8).replace("\u0000", "");
            var matcher = FIRST_HEADING.matcher(markdown);
            var fallback = filename.replaceFirst("(?i)\\.(md|markdown|txt)$", "");
            var title = matcher.find() ? matcher.group(1).trim() : fallback;
            title = title.isBlank() ? "未命名笔记" : title.substring(0, Math.min(title.length(), 200));
            return NoteResponse.from(repository.saveAndFlush(NoteEntity.imported(title, markdown, safeFilename(filename))));
        } catch (java.io.IOException exception) {
            throw new InvalidNoteFileException("无法读取上传文件");
        }
    }

    @Transactional
    @CacheEvict(cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP}, allEntries = true)
    public void delete(long id) { repository.delete(entity(id)); }

    private NoteResponse changeStatus(long id, long version, NoteStatus status) {
        var note = entity(id);
        if (note.getVersion() != version) throw new NoteVersionConflictException();
        note.changeStatus(status);
        return NoteResponse.from(repository.saveAndFlush(note));
    }

    private NoteEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("笔记不存在：" + id));
    }

    static String safeFilename(String filename) {
        var safe = filename.replace('\\', '/').substring(filename.replace('\\', '/').lastIndexOf('/') + 1)
            .replaceAll("[\\r\\n\"]", "_");
        return safe.substring(0, Math.min(safe.length(), 255));
    }
}
