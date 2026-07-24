package com.yubai.blog.common;

import java.time.Instant;

public record ApiResponse<T>(int code, String message, T data, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data, Instant.now());
    }
}
