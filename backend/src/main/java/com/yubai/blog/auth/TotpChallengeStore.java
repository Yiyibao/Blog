package com.yubai.blog.auth;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class TotpChallengeStore {
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final int CLEANUP_THRESHOLD = 1000;
    static final int MAX_ATTEMPTS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ConcurrentHashMap<String, Stored> store = new ConcurrentHashMap<>();
    private final Clock clock;

    public TotpChallengeStore(Clock clock) {
        this.clock = clock;
    }

    public record Stored(long userId, boolean remember, long createdAtEpoch, int failedAttempts) {}

    public String create(long userId, boolean remember) {
        cleanupIfNeeded();
        var raw = new byte[32];
        RANDOM.nextBytes(raw);
        var token = HexFormat.of().formatHex(raw);
        store.put(key(token), new Stored(userId, remember, clock.millis(), 0));
        return token;
    }

    public Stored find(String token) {
        if (token == null) return null;
        var mapKey = key(token);
        var stored = store.get(mapKey);
        if (stored != null && expired(stored)) {
            store.remove(mapKey, stored);
            return null;
        }
        return stored;
    }

    public void recordFailure(String token) {
        if (token == null) return;
        store.computeIfPresent(key(token), (ignored, stored) ->
            stored.failedAttempts() + 1 >= MAX_ATTEMPTS
                ? null
                : new Stored(stored.userId(), stored.remember(), stored.createdAtEpoch(), stored.failedAttempts() + 1));
    }

    public Stored consume(String token) {
        if (token == null) return null;
        var s = store.remove(key(token));
        if (s == null) return null;
        if (expired(s)) return null;
        return s;
    }

    private void cleanupIfNeeded() {
        if (store.size() > CLEANUP_THRESHOLD) {
            var cutoff = clock.millis() - TTL.toMillis();
            store.entrySet().removeIf(e -> e.getValue().createdAtEpoch() < cutoff);
        }
    }

    void reset() {
        store.clear();
    }

    private boolean expired(Stored stored) {
        return clock.millis() - stored.createdAtEpoch() > TTL.toMillis();
    }

    private static String key(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
