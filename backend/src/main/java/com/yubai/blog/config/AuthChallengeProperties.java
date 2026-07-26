package com.yubai.blog.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** L-7：后台登录人机验证三层防御参数（PoW 常开 / 失败升级图形码 / 冷却）。 */
@ConfigurationProperties(prefix = "app.auth.challenge")
public class AuthChallengeProperties {
    /** PoW 难度：SHA-256(salt + nonce) 十六进制摘要要求的前导 '0' 个数；每 +1 平均计算量 ×16。 */
    private int powDifficulty = 4;
    /** challenge 有效期，过期后必须重新获取。 */
    private Duration ttl = Duration.ofMinutes(5);
    /** 同 IP 或同用户名在失败窗口内失败次数达到该值后，challenge 升级为图形验证码。 */
    private int captchaThreshold = 3;
    /** 同 IP 在失败窗口内失败次数达到该值后进入冷却。 */
    private int cooldownThreshold = 10;
    /** 失败计数窗口。 */
    private Duration failureWindow = Duration.ofMinutes(15);
    /** 冷却时长，期间登录与取 challenge 均直接 429。 */
    private Duration cooldownDuration = Duration.ofMinutes(30);

    public int getPowDifficulty() { return powDifficulty; }
    public void setPowDifficulty(int powDifficulty) { this.powDifficulty = powDifficulty; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
    public int getCaptchaThreshold() { return captchaThreshold; }
    public void setCaptchaThreshold(int captchaThreshold) { this.captchaThreshold = captchaThreshold; }
    public int getCooldownThreshold() { return cooldownThreshold; }
    public void setCooldownThreshold(int cooldownThreshold) { this.cooldownThreshold = cooldownThreshold; }
    public Duration getFailureWindow() { return failureWindow; }
    public void setFailureWindow(Duration failureWindow) { this.failureWindow = failureWindow; }
    public Duration getCooldownDuration() { return cooldownDuration; }
    public void setCooldownDuration(Duration cooldownDuration) { this.cooldownDuration = cooldownDuration; }
}
