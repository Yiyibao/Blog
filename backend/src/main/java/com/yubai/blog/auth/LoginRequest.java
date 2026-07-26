package com.yubai.blog.auth;

import jakarta.validation.constraints.NotBlank;

/**
 * L-7：登录必须携带 challenge 凭据；challenge 为 IMAGE 类型时 captchaAnswer 必填（服务端校验）。
 * FD-9：remember 可选——true 时签发 remember-ttl（默认 24h）的长 token，配合前端 localStorage 持久化。
 */
public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    @NotBlank String challengeId,
    @NotBlank String nonce,
    String captchaAnswer,
    Boolean remember
) {
    public boolean rememberRequested() {
        return Boolean.TRUE.equals(remember);
    }
}
