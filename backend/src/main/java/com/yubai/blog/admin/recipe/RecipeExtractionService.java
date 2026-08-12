package com.yubai.blog.admin.recipe;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.common.NotFoundException;
import com.yubai.blog.dish.DishImportPreviewResponse;
import com.yubai.blog.dish.DishImportService;
import com.yubai.blog.dish.InvalidRecipeException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Coordinates durable recipe jobs; source loading, AI parsing and archive writing live elsewhere.
 */
@Service
public class RecipeExtractionService {
    private static final Logger log = LoggerFactory.getLogger(RecipeExtractionService.class);
    private static final int LEASE_SECONDS = 60;

    private final RecipeExtractionJobRepository jobRepository;
    private final DishImportService dishImportService;
    private final RecipeSourceMaterialService sourceMaterialService;
    private final RecipeExtractionPayloadService payloadService;
    private final RecipeImportPackageWriter packageWriter;
    private final ExecutorService executor;
    private final ScheduledExecutorService timeoutScheduler;
    private final ConcurrentHashMap<Long, Future<?>> runningTasks = new ConcurrentHashMap<>();

    @Autowired
    public RecipeExtractionService(
            RecipeExtractionJobRepository jobRepository,
            DishImportService dishImportService,
            RecipeSourceMaterialService sourceMaterialService,
            RecipeExtractionPayloadService payloadService,
            RecipeImportPackageWriter packageWriter,
            @Qualifier("recipeExtractionExecutor") ExecutorService executor,
            @Qualifier("recipeExtractionTimeoutScheduler")
                    ScheduledExecutorService timeoutScheduler) {
        this.jobRepository = jobRepository;
        this.dishImportService = dishImportService;
        this.sourceMaterialService = sourceMaterialService;
        this.payloadService = payloadService;
        this.packageWriter = packageWriter;
        this.executor = executor;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Transactional
    public RecipeExtractionResponse create(RecipeExtractionRequest request) {
        return create(request, null);
    }

    @Transactional
    public RecipeExtractionResponse create(RecipeExtractionRequest request, String idempotencyKey) {
        var key = parseIdempotencyKey(idempotencyKey);
        jobRepository.lockIdempotencyKey(key);
        var existing = jobRepository.findByIdempotencyKey(key);
        if (existing.isPresent()) {
            submit(existing.get().getId());
            return RecipeExtractionResponse.from(existing.get(), null);
        }

        var entity =
                new RecipeExtractionJobEntity(
                        RecipeExtractionJobEntity.SourceType.valueOf(request.sourceType()),
                        request.sourceContent(),
                        request.providerId(),
                        request.model(),
                        key);
        entity = jobRepository.saveAndFlush(entity);
        var jobId = entity.getId();
        submitAfterCommit(jobId);
        return RecipeExtractionResponse.from(entity, null);
    }

    void execute(long jobId) {
        var worker = UUID.randomUUID().toString();
        var now = Instant.now();
        if (jobRepository.claim(jobId, worker, now, leaseUntil(now)) != 1) {
            runningTasks.remove(jobId);
            return;
        }
        var entity = jobRepository.findById(jobId).orElse(null);
        if (entity == null) {
            runningTasks.remove(jobId);
            return;
        }

        ScheduledFuture<?> leaseHeartbeat =
                timeoutScheduler.scheduleAtFixedRate(
                        () -> renewLease(jobId, worker), 20, 20, TimeUnit.SECONDS);
        DishImportPreviewResponse importPreview = null;
        try {
            var source = sourceMaterialService.load(entity);
            ensureActive(jobId);

            heartbeat(jobId, worker, "正在调用 AI 提取菜谱…", 30);
            var yrecipe = payloadService.extract(entity, source);
            ensureActive(jobId);

            heartbeat(jobId, worker, "正在验证结果…", 70);
            heartbeat(jobId, worker, "正在生成导入包…", 85);
            importPreview =
                    packageWriter.write(yrecipe, source.coverBytes(), source.coverMediaType());
            ensureActive(jobId);

            if (jobRepository.succeed(jobId, worker, importPreview.token(), Instant.now()) != 1) {
                dishImportService.cancel(importPreview.token());
            }
        } catch (Exception exception) {
            if (importPreview != null) dishImportService.cancel(importPreview.token());
            log.error("Recipe extraction failed for job {}: {}", jobId, exception.toString());
            var safeMessage =
                    exception instanceof InvalidRecipeException
                            ? exception.getMessage()
                            : (exception instanceof AiServiceException
                                    ? exception.getMessage()
                                    : "提取菜谱失败，请稍后重试");
            jobRepository.failActive(
                    jobId, worker, errorCode(exception), truncate(safeMessage), Instant.now());
        } finally {
            leaseHeartbeat.cancel(false);
            runningTasks.remove(jobId);
        }
    }

    @Transactional(readOnly = true)
    public RecipeExtractionResponse getJob(Long id) {
        var entity = jobRepository.findById(id).orElseThrow(() -> new NotFoundException("提取任务不存在"));
        DishImportPreviewResponse importPreview =
                entity.getResultImportToken() == null
                        ? null
                        : dishImportService.getStagedPreview(entity.getResultImportToken());
        return RecipeExtractionResponse.from(entity, toPreview(importPreview));
    }

    @Transactional
    public void cancelJob(Long id) {
        if (!jobRepository.existsById(id)) throw new NotFoundException("提取任务不存在");
        if (jobRepository.cancelActive(id, Instant.now()) == 1) {
            var task = runningTasks.remove(id);
            if (task != null) task.cancel(true);
        }
    }

    @Transactional
    public RecipeExtractionResponse retryJob(Long id) {
        var entity = jobRepository.findById(id).orElseThrow(() -> new NotFoundException("提取任务不存在"));
        if (!entity.getStatus().equals(RecipeExtractionJobEntity.Status.FAILED.name())) {
            throw new InvalidRecipeException("只有失败的任务可以重试");
        }
        entity.retry();
        entity = jobRepository.save(entity);
        submitAfterCommit(entity.getId());
        return RecipeExtractionResponse.from(entity, null);
    }

    private void submitAfterCommit(long jobId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            submit(jobId);
                        }
                    });
        } else {
            submit(jobId);
        }
    }

    private void submit(long jobId) {
        var task =
                new FutureTask<Void>(
                        () -> {
                            execute(jobId);
                            return null;
                        });
        if (runningTasks.putIfAbsent(jobId, task) != null) return;
        try {
            executor.execute(task);
            timeoutScheduler.schedule(
                    () -> {
                        var runningTask = runningTasks.get(jobId);
                        if (runningTask != null && !runningTask.isDone()) {
                            runningTask.cancel(true);
                            jobRepository.failAnyActive(
                                    jobId, "TIMEOUT", "提取任务超时，请重试", Instant.now());
                        }
                    },
                    3,
                    TimeUnit.MINUTES);
        } catch (RejectedExecutionException exception) {
            runningTasks.remove(jobId, task);
            jobRepository.failAnyActive(jobId, "QUEUE_FULL", "当前提取任务过多，请稍后重试", Instant.now());
        }
    }

    @Scheduled(
            fixedDelayString = "${app.recipe.extraction.recovery-interval-ms:30000}",
            initialDelayString = "${app.recipe.extraction.recovery-initial-delay-ms:5000}")
    void recoverQueuedAndExpiredLeases() {
        var now = Instant.now();
        jobRepository.failExhausted(now);
        jobRepository.findRecoverableIds(now).forEach(this::submit);
    }

    private void heartbeat(long jobId, String worker, String stage, int progress) {
        var now = Instant.now();
        if (jobRepository.heartbeat(jobId, worker, stage, progress, now, leaseUntil(now)) != 1) {
            throw new InvalidRecipeException("提取任务已取消");
        }
    }

    private void renewLease(long jobId, String worker) {
        try {
            var now = Instant.now();
            jobRepository.renewLease(jobId, worker, now, leaseUntil(now));
        } catch (RuntimeException exception) {
            log.warn("Recipe extraction lease heartbeat failed for job {}", jobId);
        }
    }

    private static Instant leaseUntil(Instant now) {
        return now.plus(LEASE_SECONDS, ChronoUnit.SECONDS);
    }

    private static UUID parseIdempotencyKey(String raw) {
        if (raw == null || raw.isBlank()) return UUID.randomUUID();
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new InvalidRecipeException("Idempotency-Key 必须是 UUID");
        }
    }

    private static String errorCode(Exception exception) {
        if (exception instanceof InvalidRecipeException) return "INVALID_RECIPE";
        if (exception instanceof AiServiceException) return "AI_UPSTREAM";
        return "EXTRACTION_FAILED";
    }

    private static String truncate(String message) {
        if (message == null) return "提取菜谱失败，请稍后重试";
        return message.length() > 1000 ? message.substring(0, 1000) : message;
    }

    private void ensureActive(long jobId) {
        if (Thread.currentThread().isInterrupted()) throw new InvalidRecipeException("提取任务已取消");
        var status =
                jobRepository.findById(jobId).map(RecipeExtractionJobEntity::getStatus).orElse("");
        if (status.equals(RecipeExtractionJobEntity.Status.CANCELLED.name())) {
            throw new InvalidRecipeException("提取任务已取消");
        }
    }

    private static RecipeExtractionResponse.ImportPreview toPreview(
            DishImportPreviewResponse preview) {
        return preview == null
                ? null
                : new RecipeExtractionResponse.ImportPreview(
                        preview.token(),
                        preview.expiresAt(),
                        preview.recipe(),
                        preview.warnings(),
                        preview.categoryMatch(),
                        preview.slugAvailable(),
                        preview.coverPreviewUrl());
    }
}
