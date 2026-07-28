package com.yubai.blog.admin.ai;

/**
 * 一次 AI 调用的完整出网参数（由注册表或 env 回退解析而来，密钥已解密）。
 * apiKey 可为 null（本地无鉴权端点，如 Ollama）。
 * 4A-6：providerId 与日限额随端点携带——注册表来源才做用量审计与预算检查，
 * env 回退（providerId=null）不落审计表也不设预算。
 * providerType 标明供应商类型，用于选择下游 HTTP 客户端（OpenAI-compatible / OpenCode Server）。
 */
public record AiEndpoint(
    Long providerId,
    AiProviderType providerType,
    String baseUrl,
    String apiKey,
    String model,
    int requestTimeoutSeconds,
    int maxOutputTokens,
    int dailyRequestLimit,
    int dailyTokenLimit,
    String opencodeUsername,
    String opencodePassword,
    String opencodeAgent,
    String opencodeProviderId
) {
    /** env 回退与测试用便捷构造：无注册表来源，默认 OPENAI_COMPATIBLE。 */
    public AiEndpoint(String baseUrl, String apiKey, String model, int requestTimeoutSeconds, int maxOutputTokens) {
        this(null, AiProviderType.OPENAI_COMPATIBLE, baseUrl, apiKey, model, requestTimeoutSeconds, maxOutputTokens, 0, 0, null, null, null, null);
    }

    /** 完整构造含 OpenCode Server 参数。 */
    public AiEndpoint(Long providerId, AiProviderType providerType, String baseUrl, String apiKey, String model,
                      int requestTimeoutSeconds, int maxOutputTokens, int dailyRequestLimit, int dailyTokenLimit,
                      String opencodeUsername, String opencodePassword, String opencodeAgent, String opencodeProviderId) {
        this.providerId = providerId;
        this.providerType = providerType;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.maxOutputTokens = maxOutputTokens;
        this.dailyRequestLimit = dailyRequestLimit;
        this.dailyTokenLimit = dailyTokenLimit;
        this.opencodeUsername = opencodeUsername;
        this.opencodePassword = opencodePassword;
        this.opencodeAgent = opencodeAgent;
        this.opencodeProviderId = opencodeProviderId;
    }
}
