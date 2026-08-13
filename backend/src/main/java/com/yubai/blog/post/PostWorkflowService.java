package com.yubai.blog.post;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.config.CacheConfig;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PostWorkflowService {
    private final PostRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PostWorkflowService(PostRepository repository, JdbcTemplate jdbcTemplate, Clock clock) {
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public WorkflowResult schedule(long postId, Instant publishAt, String actor) {
        var post = entity(postId);
        PostPublicationChecks.requirePublishable(post, publishAt);
        post.schedulePublication(publishAt);
        audit(postId, "SCHEDULE", actor, publishAt.toString());
        return result(post);
    }

    @Transactional
    public WorkflowResult cancelSchedule(long postId, String actor) {
        var post = entity(postId);
        post.cancelScheduledPublication();
        audit(postId, "CANCEL_SCHEDULE", actor, null);
        return result(post);
    }

    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public List<WorkflowResult> batch(BatchRequest request, String actor) {
        var posts = repository.findAllById(request.ids());
        if (posts.size() != request.ids().stream().distinct().count()) {
            throw new NotFoundException("批量操作包含不存在的文章");
        }
        for (var post : posts) {
            if (request.action() == BatchAction.PUBLISH) {
                PostPublicationChecks.requirePublishable(post, null);
                post.changePublicationStatus(PostStatus.PUBLISHED);
            }
            if (request.action() == BatchAction.ARCHIVE)
                post.changePublicationStatus(PostStatus.ARCHIVED);
            if (request.action() == BatchAction.DRAFT)
                post.changePublicationStatus(PostStatus.DRAFT);
            if (request.action() == BatchAction.ADD_TAGS) post.addWorkflowTags(request.tags());
            audit(
                    post.getId(),
                    "BATCH_" + request.action(),
                    actor,
                    request.action() == BatchAction.ADD_TAGS
                            ? String.join(",", request.tags())
                            : null);
        }
        return posts.stream().map(PostWorkflowService::result).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> recentAudit() {
        return jdbcTemplate.query(
                """
            select id, post_id, action, actor, detail, created_at
            from post_publication_audit order by created_at desc limit 100
            """,
                (rs, row) ->
                        new AuditEntry(
                                rs.getLong("id"),
                                rs.getObject("post_id") == null ? null : rs.getLong("post_id"),
                                rs.getString("action"),
                                rs.getString("actor"),
                                rs.getString("detail"),
                                rs.getTimestamp("created_at").toInstant()));
    }

    @Scheduled(fixedDelay = 30_000)
    @Transactional
    @CacheEvict(
            cacheNames = {
                CacheConfig.GRAPH,
                CacheConfig.SITEMAP,
                CacheConfig.RSS,
                CacheConfig.RELATED_POSTS
            },
            allEntries = true)
    public void publishDue() {
        publishDueAt(clock.instant());
    }

    List<Long> publishDueAt(Instant now) {
        return jdbcTemplate.queryForList(
                """
                with due as (
                    select id
                      from posts
                     where status = 'DRAFT' and scheduled_publish_at <= ?
                     order by scheduled_publish_at, id
                     for update skip locked
                     limit 100
                ), published as (
                    update posts post
                       set status = 'PUBLISHED', scheduled_publish_at = null
                      from due
                     where post.id = due.id
                    returning post.id
                ), audited as (
                    insert into post_publication_audit (post_id, action, actor, detail)
                    select id, 'SCHEDULED_PUBLISH', 'system:scheduler', null from published
                    returning post_id
                )
                select post_id from audited order by post_id
                """,
                Long.class,
                java.sql.Timestamp.from(now));
    }

    private PostEntity entity(long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("文章不存在：" + id));
    }

    private void audit(Long postId, String action, String actor, String detail) {
        jdbcTemplate.update(
                """
            insert into post_publication_audit (post_id, action, actor, detail)
            values (?, ?, ?, ?)
            """,
                postId,
                action,
                actor == null || actor.isBlank() ? "unknown" : actor,
                detail);
    }

    private static WorkflowResult result(PostEntity post) {
        return new WorkflowResult(
                post.getId(), post.getStatus(), post.getScheduledPublishAt(), post.getTags());
    }

    public enum BatchAction {
        PUBLISH,
        ARCHIVE,
        DRAFT,
        ADD_TAGS
    }

    public record ScheduleRequest(@NotNull @Future Instant publishAt) {}

    public record BatchRequest(
            @NotEmpty @Size(max = 100) List<Long> ids,
            @NotNull BatchAction action,
            @Size(max = 20) List<@Size(max = 80) String> tags) {
        public BatchRequest {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }

        @JsonIgnore
        @AssertTrue(message = "ADD_TAGS 需要至少一个标签")
        public boolean isTagActionValid() {
            return action != BatchAction.ADD_TAGS || !tags.isEmpty();
        }
    }

    public record WorkflowResult(
            Long id, PostStatus status, Instant scheduledPublishAt, List<String> tags) {}

    public record AuditEntry(
            Long id, Long postId, String action, String actor, String detail, Instant createdAt) {}
}
