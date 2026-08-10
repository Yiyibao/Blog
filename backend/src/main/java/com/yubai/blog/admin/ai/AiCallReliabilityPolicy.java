package com.yubai.blog.admin.ai;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Shared reliability boundary for outbound AI chat calls. Non-streaming calls receive one bounded
 * retry for transient upstream failures; streaming calls are never replayed because partial output
 * may already be visible.
 */
@Component
public class AiCallReliabilityPolicy {
    private static final int FAILURE_THRESHOLD = 5;
    private static final Duration OPEN_DURATION = Duration.ofSeconds(30);
    private static final Duration RETRY_DELAY = Duration.ofMillis(100);

    private final ConcurrentHashMap<String, CircuitState> circuits = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Sleeper sleeper;

    public AiCallReliabilityPolicy() {
        this(Clock.systemUTC(), duration -> Thread.sleep(duration.toMillis()));
    }

    AiCallReliabilityPolicy(Clock clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    public <T> T execute(AiEndpoint endpoint, boolean allowRetry, Supplier<T> call) {
        var state = circuits.computeIfAbsent(key(endpoint), ignored -> new CircuitState());
        var now = clock.instant();
        if (state.isOpen(now)) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI 服务暂时不可用，请稍后重试");
        }

        int attempts = allowRetry ? 2 : 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                var result = call.get();
                state.success();
                return result;
            } catch (RuntimeException exception) {
                boolean transientFailure = isTransient(exception);
                if (!transientFailure) throw exception;
                state.failure(clock.instant());
                if (attempt == attempts) throw exception;
                if (state.isOpen(clock.instant())) throw exception;
                sleepBeforeRetry();
            }
        }
        throw new IllegalStateException("unreachable");
    }

    private void sleepBeforeRetry() {
        try {
            sleeper.sleep(RETRY_DELAY);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI 请求已中断", exception);
        }
    }

    private static boolean isTransient(RuntimeException exception) {
        if (!(exception instanceof AiServiceException serviceException)) return true;
        int status = serviceException.getStatus().value();
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private static String key(AiEndpoint endpoint) {
        return endpoint.providerId() == null
                ? endpoint.providerType() + ":" + endpoint.baseUrl()
                : "provider:" + endpoint.providerId();
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final class CircuitState {
        private final AtomicInteger consecutiveFailures = new AtomicInteger();
        private volatile Instant openUntil = Instant.EPOCH;

        boolean isOpen(Instant now) {
            if (!now.isBefore(openUntil)) {
                openUntil = Instant.EPOCH;
                return false;
            }
            return true;
        }

        void success() {
            consecutiveFailures.set(0);
            openUntil = Instant.EPOCH;
        }

        void failure(Instant now) {
            if (consecutiveFailures.incrementAndGet() >= FAILURE_THRESHOLD) {
                consecutiveFailures.set(0);
                openUntil = now.plus(OPEN_DURATION);
            }
        }
    }
}
