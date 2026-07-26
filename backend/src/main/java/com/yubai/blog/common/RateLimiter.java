package com.yubai.blog.common;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * P0-2 / P0-3：进程内固定窗口限流器。
 * 单管理员、单实例部署下无需引入分布式组件；nginx 层对登录接口另有 limit_req 双保险。
 */
@Component
public class RateLimiter {
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;

    public RateLimiter() {
        this(Clock.systemUTC());
    }

    public RateLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * @return true 表示放行；false 表示当前窗口内已超过 limit 次
     */
    public boolean tryAcquire(String key, int limit, Duration window) {
        long now = clock.millis();
        long windowMillis = window.toMillis();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.entrySet().removeIf(entry -> entry.getValue().startMillis + windowMillis <= now);
        }
        var current = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.startMillis + windowMillis <= now) {
                return new Window(now);
            }
            existing.count++;
            return existing;
        });
        return current.count <= limit;
    }

    /** 仅供测试隔离使用。 */
    public void reset() {
        windows.clear();
    }

    private static final class Window {
        final long startMillis;
        int count = 1;

        Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
