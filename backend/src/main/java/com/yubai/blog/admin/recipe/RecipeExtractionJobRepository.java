package com.yubai.blog.admin.recipe;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface RecipeExtractionJobRepository
        extends JpaRepository<RecipeExtractionJobEntity, Long> {
    Optional<RecipeExtractionJobEntity> findByIdempotencyKey(UUID idempotencyKey);

    @Query(
            value = "select pg_advisory_xact_lock(hashtextextended(cast(:key as text), 20260813))",
            nativeQuery = true)
    Object lockIdempotencyKey(@Param("key") UUID key);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.status = 'RUNNING', job.stage = '正在获取内容…', job.progress = 10,
                   job.startedAt = coalesce(job.startedAt, :now), job.finishedAt = null,
                   job.attempts = job.attempts + 1, job.errorCode = null,
                   job.safeErrorMessage = null, job.leaseOwner = :worker,
                   job.leaseUntil = :leaseUntil, job.heartbeatAt = :now
             where job.id = :id and job.attempts < 3
               and (job.status = 'QUEUED'
                    or (job.status = 'RUNNING' and job.leaseUntil < :now))
            """)
    int claim(
            @Param("id") long id,
            @Param("worker") String worker,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.stage = :stage, job.progress = :progress,
                   job.heartbeatAt = :now, job.leaseUntil = :leaseUntil
             where job.id = :id and job.status = 'RUNNING' and job.leaseOwner = :worker
            """)
    int heartbeat(
            @Param("id") long id,
            @Param("worker") String worker,
            @Param("stage") String stage,
            @Param("progress") int progress,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.heartbeatAt = :now, job.leaseUntil = :leaseUntil
             where job.id = :id and job.status = 'RUNNING' and job.leaseOwner = :worker
            """)
    int renewLease(
            @Param("id") long id,
            @Param("worker") String worker,
            @Param("now") Instant now,
            @Param("leaseUntil") Instant leaseUntil);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.status = 'SUCCEEDED', job.resultImportToken = :token,
                   job.stage = '完成', job.progress = 100, job.finishedAt = :now,
                   job.heartbeatAt = :now, job.leaseOwner = null, job.leaseUntil = null
             where job.id = :id and job.status = 'RUNNING' and job.leaseOwner = :worker
            """)
    int succeed(
            @Param("id") long id,
            @Param("worker") String worker,
            @Param("token") UUID token,
            @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.status = 'FAILED', job.errorCode = :code,
                   job.safeErrorMessage = :message, job.finishedAt = :now,
                   job.heartbeatAt = :now, job.leaseOwner = null, job.leaseUntil = null
             where job.id = :id and job.status = 'RUNNING' and job.leaseOwner = :worker
            """)
    int failActive(
            @Param("id") long id,
            @Param("worker") String worker,
            @Param("code") String code,
            @Param("message") String message,
            @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.status = 'FAILED', job.errorCode = :code,
                   job.safeErrorMessage = :message, job.finishedAt = :now,
                   job.heartbeatAt = :now, job.leaseOwner = null, job.leaseUntil = null
             where job.id = :id and job.status in ('QUEUED', 'RUNNING')
            """)
    int failAnyActive(
            @Param("id") long id,
            @Param("code") String code,
            @Param("message") String message,
            @Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.status = 'FAILED', job.errorCode = 'ATTEMPTS_EXHAUSTED',
                   job.safeErrorMessage = '提取任务重试次数已耗尽', job.finishedAt = :now,
                   job.heartbeatAt = :now, job.leaseOwner = null, job.leaseUntil = null
             where job.attempts >= 3
               and (job.status = 'QUEUED'
                    or (job.status = 'RUNNING' and job.leaseUntil < :now))
            """)
    int failExhausted(@Param("now") Instant now);

    @Transactional
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
            """
            update RecipeExtractionJobEntity job
               set job.status = 'CANCELLED', job.errorCode = 'CANCELLED',
                   job.finishedAt = :now, job.leaseOwner = null, job.leaseUntil = null
             where job.id = :id and job.status in ('QUEUED', 'RUNNING')
            """)
    int cancelActive(@Param("id") long id, @Param("now") Instant now);

    @Query(
            """
            select job.id from RecipeExtractionJobEntity job
             where job.attempts < 3
               and (job.status = 'QUEUED'
                    or (job.status = 'RUNNING' and job.leaseUntil < :now))
             order by job.createdAt
            """)
    List<Long> findRecoverableIds(@Param("now") Instant now);
}
