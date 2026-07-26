package com.yubai.blog.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.yubai.blog.config.AuthChallengeProperties;

/**
 * L-7：登录 challenge 的签发与校验。
 * 进程内存储、TTL 过期、一次性使用（验证即作废，无论成败）、绑定下发 IP；
 * 图形答案只存 SHA-256 哈希并用恒定时间比较。
 */
@Component
public class ChallengeService {
    private static final int CLEANUP_THRESHOLD = 10_000;

    private final ConcurrentHashMap<String, StoredChallenge> challenges = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final AuthChallengeProperties properties;
    private final LoginAttemptTracker attemptTracker;
    private final CaptchaImageGenerator captchaGenerator;
    private final Clock clock;

    @Autowired
    public ChallengeService(AuthChallengeProperties properties, LoginAttemptTracker attemptTracker,
                            CaptchaImageGenerator captchaGenerator) {
        this(properties, attemptTracker, captchaGenerator, Clock.systemUTC());
    }

    public ChallengeService(AuthChallengeProperties properties, LoginAttemptTracker attemptTracker,
                            CaptchaImageGenerator captchaGenerator, Clock clock) {
        this.properties = properties;
        this.attemptTracker = attemptTracker;
        this.captchaGenerator = captchaGenerator;
        this.clock = clock;
    }

    /** 按当前风险状态签发 challenge：失败达阈值升级为 IMAGE，否则纯 POW。 */
    public ChallengeResponse create(String clientIp, String username) {
        cleanupIfNeeded();
        var type = attemptTracker.requiresCaptcha(clientIp, username) ? ChallengeType.IMAGE : ChallengeType.POW;
        var challengeId = UUID.randomUUID().toString();
        var saltBytes = new byte[16];
        random.nextBytes(saltBytes);
        var salt = HexFormat.of().formatHex(saltBytes);

        String captchaImage = null;
        byte[] captchaHash = null;
        if (type == ChallengeType.IMAGE) {
            var captcha = captchaGenerator.generate();
            captchaImage = captcha.imageDataUri();
            captchaHash = sha256(normalizeAnswer(captcha.text()));
        }

        challenges.put(challengeId, new StoredChallenge(
            type, salt, captchaHash, clientIp, clock.millis() + properties.getTtl().toMillis()));
        return new ChallengeResponse(challengeId, type, salt, properties.getPowDifficulty(), captchaImage);
    }

    /**
     * 校验并作废 challenge。任何分支失败都抛同一异常，不泄露原因。
     * 若签发后风险升级（如另一会话失败触发图形码），POW 级 challenge 会被拒绝，前端需重取。
     */
    public void verify(String challengeId, String nonce, String captchaAnswer, String clientIp, String username) {
        if (challengeId == null || challengeId.isBlank() || nonce == null || nonce.isBlank()) {
            throw new ChallengeVerificationException();
        }
        var challenge = challenges.remove(challengeId);
        if (challenge == null
            || challenge.expiresAtMillis() <= clock.millis()
            || !challenge.clientIp().equals(clientIp)) {
            throw new ChallengeVerificationException();
        }
        if (challenge.type() == ChallengeType.POW && attemptTracker.requiresCaptcha(clientIp, username)) {
            throw new ChallengeVerificationException();
        }
        if (!powSatisfied(challenge.salt(), nonce)) {
            throw new ChallengeVerificationException();
        }
        if (challenge.type() == ChallengeType.IMAGE) {
            if (captchaAnswer == null || captchaAnswer.isBlank()) {
                throw new ChallengeVerificationException();
            }
            var answerHash = sha256(normalizeAnswer(captchaAnswer));
            if (!MessageDigest.isEqual(challenge.captchaHash(), answerHash)) {
                throw new ChallengeVerificationException();
            }
        }
    }

    /** 仅供测试隔离使用。 */
    public void reset() {
        challenges.clear();
    }

    private boolean powSatisfied(String salt, String nonce) {
        if (nonce.length() > 64) {
            return false;
        }
        var digest = HexFormat.of().formatHex(sha256(salt + nonce));
        return digest.startsWith("0".repeat(properties.getPowDifficulty()));
    }

    private static String normalizeAnswer(String answer) {
        return answer.trim().toLowerCase(Locale.ROOT);
    }

    private static byte[] sha256(String input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256 实现", e);
        }
    }

    private void cleanupIfNeeded() {
        if (challenges.size() > CLEANUP_THRESHOLD) {
            long now = clock.millis();
            challenges.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= now);
        }
    }

    private record StoredChallenge(ChallengeType type, String salt, byte[] captchaHash, String clientIp,
                                   long expiresAtMillis) {
    }
}
