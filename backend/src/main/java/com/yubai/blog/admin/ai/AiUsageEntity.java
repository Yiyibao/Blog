package com.yubai.blog.admin.ai;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** 4A-6：AI 调用用量审计行——只存元数据（供应商/模型/tokens/时延/状态），绝不存消息内容。 */
@Entity
@Table(name = "ai_usage")
public class AiUsageEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "provider_id", nullable = false)
    private long providerId;

    @Column(nullable = false, length = 120)
    private String model;

    @Column(name = "prompt_tokens", nullable = false)
    private int promptTokens;

    @Column(name = "completion_tokens", nullable = false)
    private int completionTokens;

    @Column(name = "latency_ms", nullable = false)
    private int latencyMs;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected AiUsageEntity() {
    }

    public static AiUsageEntity create(long providerId, String model, int promptTokens,
                                       int completionTokens, int latencyMs, String status) {
        var entity = new AiUsageEntity();
        entity.providerId = providerId;
        entity.model = model;
        entity.promptTokens = promptTokens;
        entity.completionTokens = completionTokens;
        entity.latencyMs = latencyMs;
        entity.status = status;
        entity.createdAt = Instant.now();
        return entity;
    }

    public Long getId() { return id; }
    public long getProviderId() { return providerId; }
    public String getModel() { return model; }
    public int getPromptTokens() { return promptTokens; }
    public int getCompletionTokens() { return completionTokens; }
    public int getLatencyMs() { return latencyMs; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
