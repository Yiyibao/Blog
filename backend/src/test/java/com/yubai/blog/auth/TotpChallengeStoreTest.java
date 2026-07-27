package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TotpChallengeStoreTest {

    private TotpChallengeStore store;
    private MutableClock clock;

    private static final class MutableClock extends Clock {
        private long millis = 0;

        void advance(Duration d) { millis += d.toMillis(); }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
    }

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        store = new TotpChallengeStore(clock);
    }

    @Test
    void createAndConsume() {
        var token = store.create(42L, true);
        var found = store.find(token);
        assertThat(found).isNotNull();
        assertThat(found.failedAttempts()).isZero();
        var stored = store.consume(token);
        assertThat(stored).isNotNull();
        assertThat(stored.userId()).isEqualTo(42L);
        assertThat(stored.remember()).isTrue();
    }

    @Test
    void consumeOnceOnly() {
        var token = store.create(42L, false);
        assertThat(store.consume(token)).isNotNull();
        assertThat(store.consume(token)).isNull();
    }

    @Test
    void consumeExpiredTokenReturnsNull() {
        var token = store.create(42L, false);
        clock.advance(Duration.ofMinutes(10));
        assertThat(store.consume(token)).isNull();
    }

    @Test
    void failedAttemptKeepsChallengeUntilLimit() {
        var token = store.create(42L, false);
        for (int i = 1; i < TotpChallengeStore.MAX_ATTEMPTS; i++) {
            store.recordFailure(token);
            assertThat(store.find(token).failedAttempts()).isEqualTo(i);
        }
        store.recordFailure(token);
        assertThat(store.find(token)).isNull();
    }

    @Test
    void consumeUnknownTokenReturnsNull() {
        assertThat(store.consume("nonexistent")).isNull();
    }
}
