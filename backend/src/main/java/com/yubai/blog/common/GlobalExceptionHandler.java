package com.yubai.blog.common;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import jakarta.validation.ConstraintViolationException;

import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.auth.ChallengeVerificationException;
import com.yubai.blog.auth.LoginCooldownException;
import com.yubai.blog.note.InvalidNoteFileException;
import com.yubai.blog.note.NoteVersionConflictException;
import com.yubai.blog.series.SeriesVersionConflictException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // P2-1：三类此前漏网的兜底，保证任何错误都以统一 {status,message,timestamp} 包络返回

    /** 路径/查询参数类型不匹配（如 page=abc）统一 400。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        return error(HttpStatus.BAD_REQUEST, "参数 " + exception.getName() + " 类型不合法");
    }

    /** P2-2：@Validated 方法参数校验失败（@Min/@Max 等）统一 400。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(ConstraintViolationException exception) {
        return error(HttpStatus.BAD_REQUEST, "请求参数不合法");
    }

    /** 请求体不可解析（畸形 JSON / 空 body）统一 400。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadableBody(HttpMessageNotReadableException exception) {
        return error(HttpStatus.BAD_REQUEST, "请求体不可解析");
    }

    /** 未匹配静态资源保持 404 语义（避免落入 500 兜底）。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException exception) {
        return error(HttpStatus.NOT_FOUND, "资源不存在");
    }

    /** 4B：显式抛出的 ResponseStatusException 按其自带状态码回包（否则会被下方 500 兜底吃掉）。 */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(ResponseStatusException exception) {
        var status = HttpStatus.valueOf(exception.getStatusCode().value());
        return error(status, exception.getReason() == null ? "请求不合法" : exception.getReason());
    }

    /** 最终兜底：未知异常统一 500，仅记日志不外泄内部细节。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception exception) {
        log.error("Unhandled exception", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "服务器内部错误，请稍后再试");
    }

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

    /** 4B：合集乐观锁冲突同笔记语义，统一 409。 */
    @ExceptionHandler(SeriesVersionConflictException.class)
    public ResponseEntity<Map<String, Object>> handleSeriesConflict(SeriesVersionConflictException exception) {
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

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<Map<String, Object>> handleTooManyRequests(TooManyRequestsException exception) {
        return error(HttpStatus.TOO_MANY_REQUESTS, exception.getMessage());
    }

    /** L-7：人机验证失败统一 400，文案不区分具体原因。 */
    @ExceptionHandler(ChallengeVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleChallengeVerification(ChallengeVerificationException exception) {
        return error(HttpStatus.BAD_REQUEST, exception.getMessage());
    }

    /** L-7：登录冷却 429 附带 Retry-After，便于客户端与上游按标准语义处理。 */
    @ExceptionHandler(LoginCooldownException.class)
    public ResponseEntity<Map<String, Object>> handleLoginCooldown(LoginCooldownException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
            .header("Retry-After", String.valueOf(Math.max(1, exception.getRetryAfter().toSeconds())))
            .body(Map.of(
                "status", HttpStatus.TOO_MANY_REQUESTS.value(),
                "message", exception.getMessage(),
                "timestamp", Instant.now()
            ));
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(Map.of(
            "status", status.value(),
            "message", message,
            "timestamp", Instant.now()
        ));
    }
}
