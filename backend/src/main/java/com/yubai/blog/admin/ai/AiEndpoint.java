package com.yubai.blog.admin.ai;

/**
 * 一次 AI 调用的完整出网参数（由注册表或 env 回退解析而来，密钥已解密）。
 * apiKey 可为 null（本地无鉴权端点，如 Ollama）。
 * 4A-6：providerId 与日限额随端点携带——注册表来源才做用量审计与预算检查，
 * env 回退（providerId=null）不落审计表也不设预算。
 */
public record AiEndpoint(
    Long providerId,
    String baseUrl,
    String apiKey,
    String model,
    int requestTimeoutSeconds,
    int maxOutputTokens,
    int dailyRequestLimit,
    int dailyTokenLimit
) {
    /** env 回退与测试用便捷构造：无注册表来源。 */
    public AiEndpoint(String baseUrl, String apiKey, String model, int requestTimeoutSeconds, int maxOutputTokens) {
        this(null, baseUrl, apiKey, model, requestTimeoutSeconds, maxOutputTokens, 0, 0);
    }
}
