package com.yubai.blog.auth;

/**
 * L-7：人机验证未通过（challenge 缺失/过期/重放/跨 IP/PoW 或图形答案错误/防护等级不足）。
 * 所有分支统一文案，不向攻击者泄露具体失败原因与当前防护层级。
 */
public class ChallengeVerificationException extends RuntimeException {
    public ChallengeVerificationException() {
        super("人机验证未通过，请重新验证后再试");
    }
}
