package com.yubai.blog.admin.ai;

/**
 * 一次 AI 调用的完整出网参数（由注册表或 env 回退解析而来，密钥已解密）。
 * apiKey 可为 null（本地无鉴权端点，如 Ollama）。
 */
public record AiEndpoint(
    String baseUrl,
    String apiKey,
    String model,
    int requestTimeoutSeconds,
    int maxOutputTokens
) {}
