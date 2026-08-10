package com.yubai.blog.admin.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class AiCallReliabilityPolicyTest {
    private static final AiEndpoint ENDPOINT =
            new AiEndpoint("https://api.example.com", "key", "model", 30, 1000);

    @Test
    void retriesOneTransientFailureThenSucceeds() {
        var sleeps = new AtomicInteger();
        var calls = new AtomicInteger();
        var policy =
                new AiCallReliabilityPolicy(Clock.systemUTC(), ignored -> sleeps.incrementAndGet());

        var result =
                policy.execute(
                        ENDPOINT,
                        true,
                        () -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new AiServiceException(HttpStatus.BAD_GATEWAY, "temporary");
                            }
                            return "ok";
                        });

        assertThat(result).isEqualTo("ok");
        assertThat(calls).hasValue(2);
        assertThat(sleeps).hasValue(1);
    }

    @Test
    void neverRetriesStreamingOrClientErrors() {
        var calls = new AtomicInteger();
        var policy = new AiCallReliabilityPolicy(Clock.systemUTC(), ignored -> {});

        assertThatThrownBy(
                        () ->
                                policy.execute(
                                        ENDPOINT,
                                        false,
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new AiServiceException(
                                                    HttpStatus.BAD_GATEWAY, "temporary");
                                        }))
                .isInstanceOf(AiServiceException.class);
        assertThat(calls).hasValue(1);

        assertThatThrownBy(
                        () ->
                                policy.execute(
                                        ENDPOINT,
                                        true,
                                        () -> {
                                            calls.incrementAndGet();
                                            throw new AiServiceException(
                                                    HttpStatus.BAD_REQUEST, "invalid");
                                        }))
                .isInstanceOf(AiServiceException.class);
        assertThat(calls).hasValue(2);
    }

    @Test
    void opensCircuitAfterRepeatedTransientFailures() {
        var calls = new AtomicInteger();
        var now = Instant.parse("2026-08-09T00:00:00Z");
        var policy = new AiCallReliabilityPolicy(Clock.fixed(now, ZoneOffset.UTC), ignored -> {});

        for (int index = 0; index < 3; index++) {
            assertThatThrownBy(
                            () ->
                                    policy.execute(
                                            ENDPOINT,
                                            true,
                                            () -> {
                                                calls.incrementAndGet();
                                                throw new AiServiceException(
                                                        HttpStatus.SERVICE_UNAVAILABLE, "down");
                                            }))
                    .isInstanceOf(AiServiceException.class);
        }

        assertThat(calls).hasValue(5);
        assertThatThrownBy(() -> policy.execute(ENDPOINT, true, () -> "unexpected"))
                .isInstanceOf(AiServiceException.class)
                .satisfies(
                        error ->
                                assertThat(((AiServiceException) error).getStatus())
                                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }
}
