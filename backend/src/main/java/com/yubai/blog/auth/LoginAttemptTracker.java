package com.yubai.blog.auth;

import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.yubai.blog.config.AuthChallengeProperties;

/**
 * L-7：登录失败计数器，IP 与用户名双维度（固定窗口，进程内）。
 * 失败达到 captchaThreshold 触发图形验证码，达到 cooldownThreshold 触发 IP 冷却；登录成功清零。
 */
@Component
public class LoginAttemptTracker {
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, Window> failures = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> cooldowns = new ConcurrentHashMap<>();
    private final AuthChallengeProperties properties;
    private final Clock clock;

    @Autowired
    public LoginAttemptTracker(AuthChallengeProperties properties) {
        this(properties, Clock.systemUTC());
    }

    public LoginAttemptTracker(AuthChallengeProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public void recordFailure(String clientIp, String username) {
        long now = clock.millis();
        cleanupIfNeeded(now);
        int ipCount = increment(ipKey(clientIp), now);
        increment(usernameKey(username), now);
        if (ipCount >= properties.getCooldownThreshold()) {
            cooldowns.put(clientIp, now + properties.getCooldownDuration().toMillis());
        }
    }

    public void clear(String clientIp, String username) {
        failures.remove(ipKey(clientIp));
        failures.remove(usernameKey(username));
        cooldowns.remove(clientIp);
    }

    /** 同 IP 或同用户名失败达到阈值即要求图形验证码；username 可为 null（取 challenge 时未填）。 */
    public boolean requiresCaptcha(String clientIp, String username) {
        int threshold = properties.getCaptchaThreshold();
        if (count(ipKey(clientIp)) >= threshold) {
            return true;
        }
        return username != null && !username.isBlank() && count(usernameKey(username)) >= threshold;
    }

    /** @return 冷却剩余时长；空表示未处于冷却。 */
    public Optional<Duration> cooldownRemaining(String clientIp) {
        Long until = cooldowns.get(clientIp);
        if (until == null) {
            return Optional.empty();
        }
        long remaining = until - clock.millis();
        if (remaining <= 0) {
            cooldowns.remove(clientIp, until);
            return Optional.empty();
        }
        return Optional.of(Duration.ofMillis(remaining));
    }

    /** 仅供测试隔离使用。 */
    public void reset() {
        failures.clear();
        cooldowns.clear();
    }

    private int increment(String key, long now) {
        var window = failures.compute(key, (k, existing) -> {
            if (existing == null || expired(existing, now)) {
                return new Window(now);
            }
            existing.count++;
            return existing;
        });
        return window.count;
    }

    private int count(String key) {
        var window = failures.get(key);
        if (window == null || expired(window, clock.millis())) {
            return 0;
        }
        return window.count;
    }

    private boolean expired(Window window, long now) {
        return window.startMillis + properties.getFailureWindow().toMillis() <= now;
    }

    private void cleanupIfNeeded(long now) {
        if (failures.size() > CLEANUP_THRESHOLD) {
            failures.entrySet().removeIf(entry -> expired(entry.getValue(), now));
        }
        if (cooldowns.size() > CLEANUP_THRESHOLD) {
            cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        }
    }

    private String ipKey(String clientIp) {
        return "ip:" + clientIp;
    }

    private String usernameKey(String username) {
        return "user:" + username.trim().toLowerCase(Locale.ROOT);
    }

    private static final class Window {
        final long startMillis;
        int count = 1;

        Window(long startMillis) {
            this.startMillis = startMillis;
        }
    }
}
