package com.yubai.blog.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    /** 可手动拨动的时钟。 */
    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(0);

        void advance(Duration duration) {
            millis.addAndGet(duration.toMillis());
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis.get());
        }
    }

    @Test
    void allowsUpToLimitWithinWindow() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(clock);
        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1))).as("第 %d 次应放行", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1))).as("超限应拒绝").isFalse();
    }

    @Test
    void windowResetsAfterExpiry() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(clock);
        for (int i = 0; i < 5; i++) limiter.tryAcquire("k", 5, Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1))).isFalse();

        clock.advance(Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1))).as("窗口过期后重新放行").isTrue();
    }

    @Test
    void keysAreIsolated() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(clock);
        for (int i = 0; i < 5; i++) limiter.tryAcquire("ip-a", 5, Duration.ofMinutes(1));
        assertThat(limiter.tryAcquire("ip-a", 5, Duration.ofMinutes(1))).isFalse();
        assertThat(limiter.tryAcquire("ip-b", 5, Duration.ofMinutes(1))).as("不同 key 互不影响").isTrue();
    }

    @Test
    void resetClearsAllWindows() {
        var clock = new MutableClock();
        var limiter = new RateLimiter(clock);
        for (int i = 0; i < 6; i++) limiter.tryAcquire("k", 5, Duration.ofMinutes(1));
        limiter.reset();
        assertThat(limiter.tryAcquire("k", 5, Duration.ofMinutes(1))).isTrue();
    }
}
