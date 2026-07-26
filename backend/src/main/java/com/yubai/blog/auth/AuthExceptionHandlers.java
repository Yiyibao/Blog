package com.yubai.blog.auth;

import java.time.Instant;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * FD-25：auth 包内的业务异常映射。独立成小 advice 而不并入 common.GlobalExceptionHandler，
 * 避免与并行条目在同一大文件上混改；错误信封与全局保持同构 {status, message, timestamp}。
 */
@RestControllerAdvice
public class AuthExceptionHandlers {

    @ExceptionHandler(PasswordChangeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handlePasswordChange(PasswordChangeException exception) {
        return Map.of(
            "status", 400,
            "message", exception.getMessage(),
            "timestamp", Instant.now().toString()
        );
    }
}
