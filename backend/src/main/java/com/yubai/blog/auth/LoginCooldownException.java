package com.yubai.blog.auth;

import java.time.Duration;

import com.yubai.blog.common.TooManyRequestsException;

/** L-7：IP 处于登录冷却期，映射为 429 并携带 Retry-After。 */
public class LoginCooldownException extends TooManyRequestsException {
    private final Duration retryAfter;

    public LoginCooldownException(Duration retryAfter) {
        super("失败次数过多，账号保护已启动，请 " + Math.max(1, retryAfter.toMinutes()) + " 分钟后再试");
        this.retryAfter = retryAfter;
    }

    public Duration getRetryAfter() {
        return retryAfter;
    }
}
