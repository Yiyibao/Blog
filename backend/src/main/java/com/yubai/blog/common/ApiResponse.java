package com.yubai.blog.common;

import java.time.Instant;

public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, Instant.now());
    }

    /** NB-11：创建类端点 HTTP 201 时包络 code 同步为 201，消除双状态码矛盾。 */
    public static <T> ApiResponse<T> created(T data) {
        return new ApiResponse<>(201, "created", data, Instant.now());
    }
}
