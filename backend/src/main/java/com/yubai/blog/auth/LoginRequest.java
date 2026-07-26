package com.yubai.blog.auth;

import jakarta.validation.constraints.NotBlank;

/** L-7：登录必须携带 challenge 凭据；challenge 为 IMAGE 类型时 captchaAnswer 必填（服务端校验）。 */
public record LoginRequest(
    @NotBlank String username,
    @NotBlank String password,
    @NotBlank String challengeId,
    @NotBlank String nonce,
    String captchaAnswer
) {
}
