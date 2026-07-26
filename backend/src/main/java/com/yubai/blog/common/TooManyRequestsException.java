package com.yubai.blog.common;

/** 触发限流（P0-2 / P0-3），映射为 HTTP 429。 */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
