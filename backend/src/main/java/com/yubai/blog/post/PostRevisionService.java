package com.yubai.blog.post;

import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.CacheConfig;
import java.time.Instant;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 4C：文章版本历史——管理端每次保存快照一版（由 AdminPostController 编排调用， 与 4B 删除钩子同法避免 PostService 构造器涟漪），按篇保留最近 {@link
 * #KEEP} 版； 恢复 = 把选中版本的正文字段回写文章并再快照一版（恢复本身也是一次保存）。
 */
@Service
@Transactional(readOnly = true)
public class PostRevisionService {

    static final int KEEP = 10;

    public record RevisionSummary(
            long id, String title, ContentFormat contentFormat, Instant createdAt) {}

    public record RevisionDetail(
            long id,
            String title,
            String excerpt,
            String content,
            String markdownContent,
            ContentFormat contentFormat,
            Instant createdAt) {}

    private final PostRevisionRepository revisionRepository;
    private final PostRepository postRepository;

    public PostRevisionService(
            PostRevisionRepository revisionRepository, PostRepository postRepository) {
        this.revisionRepository = revisionRepository;
        this.postRepository = postRepository;
    }

    /** 保存钩子：快照当前落库状态并截断到最近 KEEP 版。 */
    @Transactional
    public void record(long postId) {
        var post =
                postRepository
                        .findById(postId)
                        .orElseThrow(() -> new NotFoundException("文章不存在：" + postId));
        revisionRepository.save(PostRevisionEntity.snapshot(post));
        var all = revisionRepository.findAllByPostIdOrderByCreatedAtDescIdDesc(postId);
        if (all.size() > KEEP) {
            revisionRepository.deleteAll(all.subList(KEEP, all.size()));
        }
    }

    public List<RevisionSummary> list(long postId) {
        requirePost(postId);
        return revisionRepository.findSummaries(postId).stream()
                .map(
                        row ->
                                new RevisionSummary(
                                        row.getId(),
                                        row.getTitle(),
                                        row.getContentFormat(),
                                        row.getCreatedAt()))
                .toList();
    }

    public RevisionDetail findOne(long postId, long revisionId) {
        requirePost(postId);
        var revision =
                revisionRepository
                        .findByIdAndPostId(revisionId, postId)
                        .orElseThrow(() -> new NotFoundException("版本不存在：" + revisionId));
        return toDetail(revision);
    }

    /** 恢复：回写正文字段（快照内容原保存时已消毒，无需重跑管线），并产生新版本。 */
    @Transactional
    @CacheEvict(
            cacheNames = {CacheConfig.GRAPH, CacheConfig.SITEMAP, CacheConfig.RSS},
            allEntries = true)
    public PostResponse restore(long postId, long revisionId) {
        var post =
                postRepository
                        .findById(postId)
                        .orElseThrow(() -> new NotFoundException("文章不存在：" + postId));
        var revision =
                revisionRepository
                        .findByIdAndPostId(revisionId, postId)
                        .orElseThrow(() -> new NotFoundException("版本不存在：" + revisionId));
        post.applyRevision(
                revision.getTitle(),
                revision.getExcerpt(),
                revision.getContent(),
                revision.getMarkdownContent(),
                revision.getContentFormat());
        revisionRepository.save(PostRevisionEntity.snapshot(post));
        var all = revisionRepository.findAllByPostIdOrderByCreatedAtDescIdDesc(postId);
        if (all.size() > KEEP) {
            revisionRepository.deleteAll(all.subList(KEEP, all.size()));
        }
        if (post.getStatus() == PostStatus.PUBLISHED || post.getScheduledPublishAt() != null) {
            PostPublicationChecks.requirePublishable(post, post.getScheduledPublishAt());
        }
        postRepository.flush();
        return PostResponse.from(post);
    }

    private static RevisionDetail toDetail(PostRevisionEntity revision) {
        return new RevisionDetail(
                revision.getId(),
                revision.getTitle(),
                revision.getExcerpt(),
                revision.getContent(),
                revision.getMarkdownContent(),
                revision.getContentFormat(),
                revision.getCreatedAt());
    }

    private void requirePost(long postId) {
        if (!postRepository.existsById(postId)) {
            throw new NotFoundException("文章不存在：" + postId);
        }
    }
}
