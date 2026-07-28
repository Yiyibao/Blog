package com.yubai.blog.admin.recipe;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "recipe_extraction_jobs")
public class RecipeExtractionJobEntity {
    public enum SourceType { TEXT, WEB_URL }
    public enum Status { QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "source_type", nullable = false, length = 20)
    private String sourceType;

    @Column(name = "source_content", nullable = false, columnDefinition = "text")
    private String sourceContent;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 50)
    private String stage;

    @Column(nullable = false)
    private int progress;

    @Column(name = "provider_id")
    private Long providerId;

    @Column(length = 120)
    private String model;

    @Column(name = "result_import_token")
    private UUID resultImportToken;

    @Column(name = "safe_error_message", length = 1000)
    private String safeErrorMessage;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Version
    @Column(nullable = false)
    private int version;

    protected RecipeExtractionJobEntity() {}

    public RecipeExtractionJobEntity(SourceType sourceType, String sourceContent, Long providerId, String model) {
        this.sourceType = sourceType.name();
        this.sourceContent = sourceContent;
        this.providerId = providerId;
        this.model = model;
        this.status = Status.QUEUED.name();
        this.progress = 0;
        this.attempts = 0;
    }

    @PrePersist
    void onCreate() {
        this.createdAt = Instant.now();
    }

    public void start() {
        this.status = Status.RUNNING.name();
        this.startedAt = Instant.now();
        this.attempts++;
    }

    public void succeed(UUID resultImportToken) {
        this.status = Status.SUCCEEDED.name();
        this.resultImportToken = resultImportToken;
        this.progress = 100;
        this.finishedAt = Instant.now();
    }

    public void fail(String safeMessage) {
        this.status = Status.FAILED.name();
        this.safeErrorMessage = safeMessage != null && safeMessage.length() > 1000 ? safeMessage.substring(0, 1000) : safeMessage;
        this.finishedAt = Instant.now();
    }

    public void cancel() {
        this.status = Status.CANCELLED.name();
        this.finishedAt = Instant.now();
    }

    public void updateStage(String stage, int progress) {
        this.stage = stage;
        this.progress = progress;
    }

    public Long getId() { return id; }
    public String getSourceType() { return sourceType; }
    public String getSourceContent() { return sourceContent; }
    public String getStatus() { return status; }
    public String getStage() { return stage; }
    public int getProgress() { return progress; }
    public Long getProviderId() { return providerId; }
    public String getModel() { return model; }
    public UUID getResultImportToken() { return resultImportToken; }
    public String getSafeErrorMessage() { return safeErrorMessage; }
    public int getAttempts() { return attempts; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
}
