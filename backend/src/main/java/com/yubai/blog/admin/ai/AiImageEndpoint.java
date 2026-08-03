package com.yubai.blog.admin.ai;

/** Immutable, already-resolved server-side image provider configuration. */
public record AiImageEndpoint(
    String provider,
    String baseUrl,
    String apiKey,
    String model,
    String wireApi,
    String headerName,
    String headerValue,
    int requestTimeoutSeconds
) {
}
