package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yubai.blog.config.AuthChallengeProperties;

class LoginAttemptTrackerTest {

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

    private static final String IP = "203.0.113.10";

    private MutableClock clock;
    private AuthChallengeProperties properties;
    private LoginAttemptTracker tracker;

    @BeforeEach
    void setUp() {
        clock = new MutableClock();
        properties = new AuthChallengeProperties();
        tracker = new LoginAttemptTracker(properties, clock);
    }

    @Test
    void requiresCaptchaAfterThresholdByIp() {
        for (int i = 0; i < properties.getCaptchaThreshold() - 1; i++) {
            tracker.recordFailure(IP, "u" + i);
        }
        assertThat(tracker.requiresCaptcha(IP, null)).as("未达阈值不触发").isFalse();
        tracker.recordFailure(IP, "another");
        assertThat(tracker.requiresCaptcha(IP, null)).as("同 IP 达阈值触发").isTrue();
    }

    @Test
    void requiresCaptchaByUsernameAcrossIps() {
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure("198.51.100." + i, "Admin");
        }
        assertThat(tracker.requiresCaptcha(IP, "admin")).as("用户名维度大小写不敏感、跨 IP 生效").isTrue();
        assertThat(tracker.requiresCaptcha(IP, "someone-else")).isFalse();
        assertThat(tracker.requiresCaptcha(IP, null)).as("该 IP 自身无失败记录").isFalse();
    }

    @Test
    void countsResetAfterWindowExpires() {
        for (int i = 0; i < properties.getCaptchaThreshold(); i++) {
            tracker.recordFailure(IP, "admin");
        }
        assertThat(tracker.requiresCaptcha(IP, "admin")).isTrue();
        clock.advance(properties.getFailureWindow().plusSeconds(1));
        assertThat(tracker.requiresCaptcha(IP, "admin")).as("窗口过期后计数归零").isFalse();
    }

    @Test
    void cooldownStartsAtThresholdAndExpires() {
        for (int i = 0; i < properties.getCooldownThreshold() - 1; i++) {
            tracker.recordFailure(IP, "admin");
        }
        assertThat(tracker.cooldownRemaining(IP, "admin")).as("未达冷却阈值").isEmpty();
        tracker.recordFailure(IP, "admin");
        assertThat(tracker.cooldownRemaining(IP, "admin")).as("达阈值进入冷却").isPresent();
        assertThat(tracker.cooldownRemaining(IP, "admin").orElseThrow())
            .isLessThanOrEqualTo(properties.getCooldownDuration());

        clock.advance(properties.getCooldownDuration().plusSeconds(1));
        assertThat(tracker.cooldownRemaining(IP, "admin")).as("冷却到期自动解除").isEmpty();
    }

    @Test
    void successClearsCountsAndCooldown() {
        for (int i = 0; i < properties.getCooldownThreshold(); i++) {
            tracker.recordFailure(IP, "admin");
        }
        assertThat(tracker.cooldownRemaining(IP, "admin")).isPresent();
        tracker.clear(IP, "admin");
        assertThat(tracker.cooldownRemaining(IP, "admin")).as("成功登录解除冷却").isEmpty();
        assertThat(tracker.requiresCaptcha(IP, "admin")).as("成功登录清零计数").isFalse();
    }

    @Test
    void cooldownIsScopedToIpUsernamePair() {
        // FD-0：两人共用家庭 Wi-Fi 时，一方口令连错不应把另一方锁死 30 分钟
        for (int i = 0; i < properties.getCooldownThreshold(); i++) {
            tracker.recordFailure(IP, "Admin");
        }
        assertThat(tracker.cooldownRemaining(IP, "admin")).as("用户名大小写不敏感命中").isPresent();
        assertThat(tracker.cooldownRemaining(IP, "partner")).as("同 IP 其他用户名不受牵连").isEmpty();
        assertThat(tracker.cooldownRemaining(IP, null)).as("未提供用户名不判冷却").isEmpty();
        assertThat(tracker.cooldownRemaining(IP, "  ")).as("空白用户名不判冷却").isEmpty();
        assertThat(tracker.cooldownRemaining("198.51.100.99", "admin")).as("其他 IP 不受牵连").isEmpty();
    }

    @Test
    void rotatingUsernamesDoesNotTriggerPairCooldownButStillEscalatesCaptcha() {
        // FD-0：冷却改按 (IP, 用户名) 对计数；轮换用户名的攻击者由验证码层（IP 维度）+ nginx 限速兜底
        for (int i = 0; i < properties.getCooldownThreshold(); i++) {
            tracker.recordFailure(IP, "user-" + i);
        }
        assertThat(tracker.cooldownRemaining(IP, "user-0")).as("单个配对未达阈值").isEmpty();
        assertThat(tracker.requiresCaptcha(IP, null)).as("IP 维度失败计数仍触发验证码").isTrue();
    }
}
