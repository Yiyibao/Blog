package com.yubai.blog.auth;

/**
 * FD-25：改密业务失败（当前口令不对/新口令不达标）。
 * 刻意不用 401——前端拦截器会把 401 当会话失效直接清登录态，这里只是表单级错误。
 */
public class PasswordChangeException extends RuntimeException {
    public PasswordChangeException(String message) {
        super(message);
    }
}
