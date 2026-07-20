package com.yubai.blog.common;

import java.time.Instant;

public record ApiResponse<T>(T data, Instant timestamp) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(data, Instant.now());
    }
}
