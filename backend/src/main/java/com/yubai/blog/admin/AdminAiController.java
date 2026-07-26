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
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final AiChatService chatService;
    private final AiProperties properties;
    private final ExecutorService streamExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    public AdminAiController(AiChatService chatService, AiProperties properties,
                             @Qualifier("aiStreamExecutor") ExecutorService streamExecutor,
                             @Qualifier("aiSseHeartbeatScheduler") ScheduledExecutorService heartbeatScheduler) {
        this.chatService = chatService;
        this.properties = properties;
        this.streamExecutor = streamExecutor;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.ok(chatService.chat(request));
    }

    /**
     * 4A-2：SSE 流式对话。校验错误在建立流之前以普通 HTTP 错误返回；
     * 建流后的错误以 error 事件下发。JWT 鉴权走 Authorization 头（前端 fetch 手工解析 SSE）。
     */
    @PostMapping("/chat/stream")
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request, HttpServletResponse response) {
        chatService.validateLimits(request);
        // nginx 对带此头的响应关闭 proxy_buffering，SSE 不需要额外的 nginx 配置
        response.setHeader("X-Accel-Buffering", "no");
        var emitter = new SseEmitter(Duration.ofSeconds(properties.getRequestTimeout() + 30L).toMillis());
        // 心跳 15 秒一条注释帧，防止代理断开空闲连接。tryLock：流线程正在写说明连接不空闲，
        // 直接跳过本次心跳——慢客户端不会阻塞共享心跳线程（避免跨连接队头阻塞）。
        var sendLock = new ReentrantLock();
        var heartbeatRef = new AtomicReference<ScheduledFuture<?>>();
        var heartbeat = heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (!sendLock.tryLock()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (IOException | IllegalStateException exception) {
                // 客户端断开或流已完成：取消自身，防止定时任务在容器不回调时永久驻留
                cancelHeartbeat(heartbeatRef.get());
            } finally {
                sendLock.unlock();
            }
        }, HEARTBEAT_INTERVAL_SECONDS, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        heartbeatRef.set(heartbeat);

        var worker = streamExecutor.submit(() -> {
            try {
                chatService.stream(request, new AiStreamListener() {
                    @Override
                    public void onDelta(String content) {
                        sendLocked(emitter, sendLock, SseEmitter.event().name("delta").data(Map.of("content", content)));
                    }

                    @Override
                    public void onComplete(ChatResponse chatResponse) {
                        sendLocked(emitter, sendLock, SseEmitter.event().name("done")
                            .data(new StreamDonePayload(chatResponse.model(), chatResponse.usage())));
                    }
                });
                emitter.complete();
            } catch (AiServiceException exception) {
                sendErrorEvent(emitter, sendLock, exception.getStatus().value(), exception.getMessage());
            } catch (Exception exception) {
                sendErrorEvent(emitter, sendLock, HttpStatus.BAD_GATEWAY.value(), "AI service request failed");
            }
        });
        emitter.onCompletion(() -> cancelHeartbeat(heartbeat));
        // 超时/出错时同时中断工作线程：客户端已不在，尽早停读上游、停止 token 消耗
        emitter.onTimeout(() -> {
            cancelHeartbeat(heartbeat);
            cancelWorker(worker);
        });
        emitter.onError(ignored -> {
            cancelHeartbeat(heartbeat);
            cancelWorker(worker);
        });
        return emitter;
    }

    private static void sendLocked(SseEmitter emitter, ReentrantLock sendLock, SseEmitter.SseEventBuilder event) {
        sendLock.lock();
        try {
            emitter.send(event);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            sendLock.unlock();
        }
    }

    private static void sendErrorEvent(SseEmitter emitter, ReentrantLock sendLock, int status, String message) {
        sendLock.lock();
        try {
            emitter.send(SseEmitter.event().name("error").data(Map.of("status", status, "message", message)));
        } catch (IOException | IllegalStateException ignored) {
            // 客户端已断开
        } finally {
            sendLock.unlock();
        }
        emitter.complete();
    }

    private static void cancelHeartbeat(ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }

    /** cancel(true)：以中断标志通知工作线程停读上游（parseSseStream 逐行检查）。 */
    private static void cancelWorker(Future<?> future) {
        if (future != null) {
            future.cancel(true);
        }
    }

    /** done 事件负载：正文已通过 delta 下发，这里只带模型与用量。 */
    record StreamDonePayload(String model, ChatResponse.Usage usage) {
    }
}
