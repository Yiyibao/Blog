package com.yubai.blog.admin;

import com.yubai.blog.admin.ai.AiChatService;
import com.yubai.blog.admin.ai.AiServiceException;
import com.yubai.blog.admin.ai.AiStreamListener;
import com.yubai.blog.admin.ai.ChatRequest;
import com.yubai.blog.admin.ai.ChatResponse;
import com.yubai.blog.common.ApiResponse;
import com.yubai.blog.config.AiProperties;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/admin/ai")
public class AdminAiController {
    /** SSE 心跳：15 秒一条注释帧，防止代理断开空闲连接。 */
    private static final ScheduledExecutorService HEARTBEAT = Executors.newSingleThreadScheduledExecutor(runnable -> {
        var thread = new Thread(runnable, "ai-sse-heartbeat");
        thread.setDaemon(true);
        return thread;
    });

    private final AiChatService chatService;
    private final AiProperties properties;
    private final ExecutorService streamExecutor;

    public AdminAiController(AiChatService chatService, AiProperties properties,
                             @Qualifier("aiStreamExecutor") ExecutorService streamExecutor) {
        this.chatService = chatService;
        this.properties = properties;
        this.streamExecutor = streamExecutor;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        validateLimits(request);
        return ApiResponse.ok(chatService.chat(request));
    }

    /**
     * 4A-2：SSE 流式对话。校验错误在建立流之前以普通 HTTP 错误返回；
     * 建流后的错误以 error 事件下发。JWT 鉴权走 Authorization 头（前端 fetch 手工解析 SSE）。
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        validateLimits(request);
        // nginx 对带此头的响应关闭 proxy_buffering，SSE 不需要额外的 nginx 配置
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(Duration.ofSeconds(properties.getRequestTimeout() + 30L).toMillis());
        var sendLock = new Object();
        var heartbeat = HEARTBEAT.scheduleAtFixedRate(() -> {
            synchronized (sendLock) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException ignored) {
                    // 客户端断开或已完成；由主流程收尾
                }
            }
        }, 15, 15, TimeUnit.SECONDS);
        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> heartbeat.cancel(false));
        emitter.onError(ignored -> heartbeat.cancel(false));

        streamExecutor.execute(() -> {
            try {
                chatService.stream(request, new AiStreamListener() {
                    @Override
                    public void onDelta(String content) {
                        synchronized (sendLock) {
                            try {
                                emitter.send(SseEmitter.event().name("delta").data(Map.of("content", content)));
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        }
                    }

                    @Override
                    public void onComplete(ChatResponse chatResponse) {
                        synchronized (sendLock) {
                            try {
                                emitter.send(SseEmitter.event().name("done")
                                    .data(new StreamDonePayload(chatResponse.model(), chatResponse.usage())));
                            } catch (IOException exception) {
                                throw new UncheckedIOException(exception);
                            }
                        }
                    }
                });
                emitter.complete();
            } catch (AiServiceException exception) {
                sendErrorEvent(emitter, sendLock, exception.getStatus().value(), exception.getMessage());
            } catch (Exception exception) {
                sendErrorEvent(emitter, sendLock, HttpStatus.BAD_GATEWAY.value(), "AI service request failed");
            }
        });
        return emitter;
    }

    private static void sendErrorEvent(SseEmitter emitter, Object sendLock, int status, String message) {
        synchronized (sendLock) {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("status", status, "message", message)));
            } catch (IOException | IllegalStateException ignored) {
                // 客户端已断开
            }
        }
        emitter.complete();
    }

    private void validateLimits(ChatRequest request) {
        if (request.messages().size() > properties.getMaxHistoryMessages()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message count exceeds maximum of " + properties.getMaxHistoryMessages());
        }
        if (request.messages().stream().anyMatch(message -> message.content().length() > properties.getMaxInputChars())) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Message length exceeds maximum of " + properties.getMaxInputChars());
        }
        var totalChars = request.messages().stream().mapToInt(m -> m.content().length()).sum();
        if (totalChars > properties.getMaxTotalChars()) {
            throw new AiServiceException(HttpStatus.BAD_REQUEST,
                "Total content length exceeds maximum of " + properties.getMaxTotalChars());
        }
    }

    /** done 事件负载：正文已通过 delta 下发，这里只带模型与用量。 */
    record StreamDonePayload(String model, ChatResponse.Usage usage) {
    }
}
