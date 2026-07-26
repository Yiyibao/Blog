package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yubai.blog.config.AuthChallengeProperties;

class ChallengeServiceTest {

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

    /** 测试用固定答案的图形码生成器，避免解析真实图片。 */
    private static final class FixedCaptchaGenerator extends CaptchaImageGenerator {
        @Override
        public Captcha generate() {
            return new Captcha("AB3CD", "data:image/png;base64,test");
        }
    }

    private static final String IP = "203.0.113.10";

    private MutableClock clock;
    private AuthChallengeProperties properties;
    private LoginAttemptTracker tracker;
    private ChallengeService service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        properties = new AuthChallengeProperties();
        properties.setPowDifficulty(1);
        tracker = new LoginAttemptTracker(properties, clock);
        service = new ChallengeService(properties, tracker, new FixedCaptchaGenerator(), clock);
    }

    private String solvePow(String salt) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var prefix = "0".repeat(properties.getPowDifficulty());
            for (long nonce = 0; ; nonce++) {
                var candidate = Long.toString(nonce);
                var hash = HexFormat.of().formatHex(digest.digest((salt + candidate).getBytes(StandardCharsets.UTF_8)));
                if (hash.startsWith(prefix)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void freshClientGetsPowChallengeAndPasses() {
        var challenge = service.create(IP, "admin");
        assertThat(challenge.type()).as("无失败记录时应为纯 PoW").isEqualTo(ChallengeType.POW);
        assertThat(challenge.captchaImage()).isNull();

        var nonce = solvePow(challenge.salt());
        assertThatCode(() -> service.verify(challenge.challengeId(), nonce, null, IP, "admin"))
            .doesNotThrowAnyException();
    }

    @Test
    void wrongNonceIsRejected() {
        var challenge = service.create(IP, "admin");
        var badNonce = findFailingNonce(challenge.salt(), "0".repeat(properties.getPowDifficulty()));
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), badNonce, null, IP, "admin"))
            .isInstanceOf(ChallengeVerificationException.class);
    }

    /** 确定性地找一个不满足难度前缀的 nonce，避免低难度下随机字符串碰巧通过。 */
    private static String findFailingNonce(String salt, String prefix) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (long nonce = 0; ; nonce++) {
                var candidate = "bad-" + nonce;
                var hash = HexFormat.of().formatHex(digest.digest((salt + candidate).getBytes(StandardCharsets.UTF_8)));
                if (!hash.startsWith(prefix)) {
                    return candidate;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void challengeIsSingleUse() {
        var challenge = service.create(IP, "admin");
        var nonce = solvePow(challenge.salt());
        service.verify(challenge.challengeId(), nonce, null, IP, "admin");
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), nonce, null, IP, "admin"))
            .as("重放同一 challenge 必须被拒绝")
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void failedVerificationAlsoConsumesChallenge() {
        var challenge = service.create(IP, "admin");
        var badNonce = findFailingNonce(challenge.salt(), "0".repeat(properties.getPowDifficulty()));
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), badNonce, null, IP, "admin"))
            .isInstanceOf(ChallengeVerificationException.class);
        var nonce = solvePow(challenge.salt());
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), nonce, null, IP, "admin"))
            .as("验证失败同样作废，不给二次机会")
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void expiredChallengeIsRejected() {
        var challenge = service.create(IP, "admin");
        var nonce = solvePow(challenge.salt());
        clock.advance(properties.getTtl().plusSeconds(1));
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), nonce, null, IP, "admin"))
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void challengeIsBoundToIssuingIp() {
        var challenge = service.create(IP, "admin");
        var nonce = solvePow(challenge.salt());
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), nonce, null, "198.51.100.9", "admin"))
            .as("跨 IP 使用 challenge 必须被拒绝")
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void unknownChallengeIdIsRejected() {
        assertThatThrownBy(() -> service.verify("no-such-id", "0", null, IP, "admin"))
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void escalatesToImageAfterIpFailures() {
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure(IP, "admin");
        }
        var challenge = service.create(IP, null);
        assertThat(challenge.type()).as("同 IP 失败达阈值后应升级图形码").isEqualTo(ChallengeType.IMAGE);
        assertThat(challenge.captchaImage()).startsWith("data:image/png;base64,");
    }

    @Test
    void escalatesToImageAfterUsernameFailuresFromOtherIp() {
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure("198.51.100." + i, "admin");
        }
        assertThat(service.create(IP, "admin").type())
            .as("同用户名跨 IP 失败达阈值也应升级图形码")
            .isEqualTo(ChallengeType.IMAGE);
        assertThat(service.create(IP, "other-user").type())
            .as("其他用户名不受影响")
            .isEqualTo(ChallengeType.POW);
    }

    @Test
    void imageChallengeAnswerIsCaseInsensitive() {
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure(IP, "admin");
        }
        var challenge = service.create(IP, "admin");
        var nonce = solvePow(challenge.salt());
        assertThatCode(() -> service.verify(challenge.challengeId(), nonce, "ab3cd", IP, "admin"))
            .as("图形码答案大小写不敏感")
            .doesNotThrowAnyException();
    }

    @Test
    void imageChallengeRejectsWrongOrMissingAnswer() {
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure(IP, "admin");
        }
        var first = service.create(IP, "admin");
        assertThatThrownBy(() -> service.verify(first.challengeId(), solvePow(first.salt()), "WRONG", IP, "admin"))
            .isInstanceOf(ChallengeVerificationException.class);
        var second = service.create(IP, "admin");
        assertThatThrownBy(() -> service.verify(second.challengeId(), solvePow(second.salt()), null, IP, "admin"))
            .as("升级后缺少图形答案必须被拒绝")
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void stalePowChallengeRejectedAfterEscalation() {
        var challenge = service.create(IP, "admin");
        var nonce = solvePow(challenge.salt());
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure(IP, "admin");
        }
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), nonce, null, IP, "admin"))
            .as("签发后风险升级，旧的纯 PoW challenge 不再有效")
            .isInstanceOf(ChallengeVerificationException.class);
    }

    @Test
    void difficultyParameterTakesEffect() {
        properties.setPowDifficulty(2);
        var challenge = service.create(IP, "admin");
        assertThat(challenge.difficulty()).isEqualTo(2);

        // 找一个只满足难度 1、不满足难度 2 的 nonce，验证难度参数真正参与校验
        String weakNonce = null;
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (long nonce = 0; ; nonce++) {
                var candidate = Long.toString(nonce);
                var hash = HexFormat.of().formatHex(
                    digest.digest((challenge.salt() + candidate).getBytes(StandardCharsets.UTF_8)));
                if (hash.startsWith("0") && !hash.startsWith("00")) {
                    weakNonce = candidate;
                    break;
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var finalWeakNonce = weakNonce;
        assertThatThrownBy(() -> service.verify(challenge.challengeId(), finalWeakNonce, null, IP, "admin"))
            .isInstanceOf(ChallengeVerificationException.class);
    }
}
