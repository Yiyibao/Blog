package com.yubai.blog.auth;

/**
 * L-7：登录 challenge 下发内容。type 为 POW 时 captchaImage 为 null；
 * type 为 IMAGE 时前端需同时提交 PoW nonce 与图形答案。
 */
public record ChallengeResponse(
    String challengeId,
    ChallengeType type,
    String salt,
    int difficulty,
    String captchaImage
) {
}
