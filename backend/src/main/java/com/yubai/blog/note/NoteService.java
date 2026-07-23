package com.yubai.blog.note;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.common.PageResponse;
import com.yubai.blog.common.PageRequests;

@Service
@Transactional(readOnly = true)
public class NoteService {
    private static final int MAX_IMPORT_BYTES = 2_000_000;
    private static final Pattern FIRST_HEADING = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private final NoteRepository repository;

    public NoteService(NoteRepository repository) { this.repository = repository; }

    public PageResponse<NoteResponse> findAll(NoteStatus status, int page, int size) {
        var pageable = PageRequests.of(page, size);
        var result = status == null
            ? repository.findAllByOrderByUpdatedAtDesc(pageable)
            : repository.findAllByStatusOrderByUpdatedAtDesc(status, pageable);
        return PageResponse.from(result.map(NoteResponse::from));
    }

    public PageResponse<NoteResponse> findPublished(int page, int size) {
        return PageResponse.from(repository.findAllByStatusOrderByUpdatedAtDesc(
            NoteStatus.PUBLISHED, PageRequests.of(page, size)).map(NoteResponse::from));
    }

    public NoteResponse findOne(long id) { return NoteResponse.from(entity(id)); }

    public NoteResponse findPublishedOne(long id) {
        var note = entity(id);
        if (note.getStatus() != NoteStatus.PUBLISHED) throw new NotFoundException("笔记不存在：" + id);
        return NoteResponse.from(note);
    }

    @Transactional
    public NoteResponse create(NoteRequest request) { return NoteResponse.from(repository.saveAndFlush(NoteEntity.create(request))); }

    @Transactional
    public NoteResponse update(long id, NoteRequest request) {
        var note = entity(id);
        if (note.getVersion() != request.version()) throw new NoteVersionConflictException();
        note.update(request);
        return NoteResponse.from(repository.saveAndFlush(note));
    }

    @Transactional
    public NoteResponse publish(long id, long version) {
        return changeStatus(id, version, NoteStatus.PUBLISHED);
    }

    @Transactional
    public NoteResponse unpublish(long id, long version) {
        return changeStatus(id, version, NoteStatus.DRAFT);
    }

    @Transactional
    public NoteResponse archive(long id, long version) {
        return changeStatus(id, version, NoteStatus.ARCHIVED);
    }

    @Transactional
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
