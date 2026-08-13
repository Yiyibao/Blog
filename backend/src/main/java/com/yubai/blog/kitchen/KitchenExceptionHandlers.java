package com.yubai.blog.kitchen;

import java.time.Instant;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * FD-10：kitchen 包内业务异常映射（独立小 advice，不并入 common 大文件）； 错误信封与全局同构 {status, message, timestamp}。
 * ObjectOptimisticLockingFailure 是 FORCE_INCREMENT 在真并发下的另一种冲突形态，语义同 409。
 */
// 必须先于 common.GlobalExceptionHandler 的 Exception 兜底被匹配（advice 无序时按 bean 名序，K > G 会输）
@Order(0)
@RestControllerAdvice
public class KitchenExceptionHandlers {

    @ExceptionHandler({
        MenuVersionConflictException.class,
        ShoppingListVersionConflictException.class,
        ObjectOptimisticLockingFailureException.class
    })
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, Object> handleVersionConflict(Exception exception) {
        var message = exception.getMessage();
        if (message == null || message.isBlank()) message = "菜单刚被对方更新过，请刷新后再提交";
        return Map.of("status", 409, "message", message, "timestamp", Instant.now().toString());
    }

    @ExceptionHandler(KitchenBadRequestException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, Object> handleBadRequest(KitchenBadRequestException exception) {
        return Map.of(
                "status",
                400,
                "message",
                exception.getMessage(),
                "timestamp",
                Instant.now().toString());
    }
}
