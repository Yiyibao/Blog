package com.yubai.blog.common;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.note.InvalidNoteFileException;
import com.yubai.blog.note.NoteVersionConflictException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(NotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
            "status", HttpStatus.NOT_FOUND.value(),
            "message", exception.getMessage(),
            "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<Map<String, Object>> handleBadCredentials() {
        return error(HttpStatus.UNAUTHORIZED, "用户名或密码错误");
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication() {
        return error(HttpStatus.UNAUTHORIZED, "未登录或登录已过期，请重新登录");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied() {
        return error(HttpStatus.FORBIDDEN, "没有权限执行此操作");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException exception) {
        var details = exception.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(
                error -> error.getField(),
                error -> error.getDefaultMessage() == null ? "参数不合法" : error.getDefaultMessage(),
                (first, ignored) -> first
            ));
        return ResponseEntity.badRequest().body(Map.of(
            "status", HttpStatus.BAD_REQUEST.value(),
            "message", "请求参数不合法",
            "details", details,
            "timestamp", Instant.now()
        ));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConflict() {
        return error(HttpStatus.CONFLICT, "数据与现有记录冲突，请检查唯一字段");
    }

    @ExceptionHandler(InvalidNoteFileException.class)
    public ResponseEntity<Map<String, Object>> handleInvalidFile(InvalidNoteFileException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    @ExceptionHandler(NoteVersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleNoteConflict(NoteVersionConflictException exception) {
        return error(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> handleUploadTooLarge() {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "上传文件不能超过 8 MB");
    }

    @ExceptionHandler(AiServiceException.class)
    public ResponseEntity<Map<String, Object>> handleAiService(AiServiceException exception) {
        return error(exception.getStatus(), exception.getMessage());
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "status", status.value(),
            "message", message,
            "timestamp", Instant.now()
        ));
    }
}
