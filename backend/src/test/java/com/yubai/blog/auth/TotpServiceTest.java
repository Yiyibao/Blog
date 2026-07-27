package com.yubai.blog.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    private static final String JWT_SECRET = "0123456789abcdef0123456789abcdef0123456";
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final Instant T0 = Instant.ofEpochSecond(0);
    private static final Instant T1 = Instant.ofEpochSecond(30);
    private static final Instant T2 = Instant.ofEpochSecond(60);

    private TotpService service;
    private TotpService fixedClockService;
    private Clock fixedClock;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.ofEpochSecond(1234567890L), ZoneOffset.UTC);
        service = new TotpService(JWT_SECRET, Clock.systemUTC());
        fixedClockService = new TotpService(JWT_SECRET, fixedClock);
    }

    @Test
    void generateSecretIsBase32AndCorrectLength() {
        var secret = service.generateSecret();
        assertThat(secret).matches("[A-Z2-7]+");
        assertThat(secret).hasSize(32);
    }

    @Test
    void generateSecretIsRandom() {
        var s1 = service.generateSecret();
        var s2 = service.generateSecret();
        assertThat(s1).isNotEqualTo(s2);
    }

    @Test
    void getTotpCodeIsSixDigits() {
        var secret = service.generateSecret();
        var code = service.getTotpCode(secret, Instant.now());
        assertThat(code).matches("\\d{6}");
    }

    @Test
    void matchesRfc6238Sha1VectorTruncatedToSixDigits() {
        assertThat(service.getTotpCode(RFC_SECRET, Instant.ofEpochSecond(59))).isEqualTo("287082");
    }

    @Test
    void getTotpCodeChangesOverTime() {
        var secret = service.generateSecret();
        var code1 = service.getTotpCode(secret, T0);
        var code2 = service.getTotpCode(secret, T1);
        assertThat(code1).isNotEqualTo(code2);
    }

    @Test
    void verifyAcceptsCurrentTimeStep() {
        var now = fixedClock.instant();
        var code = fixedClockService.getTotpCode(RFC_SECRET, now);
        assertThat(fixedClockService.verify(code, RFC_SECRET)).isTrue();
    }

    @Test
    void verifyAcceptsAdjacentTimeStep() {
        var adjacent = fixedClock.instant().plus(TotpService.TOTP_PERIOD);
        var code = fixedClockService.getTotpCode(RFC_SECRET, adjacent);
        assertThat(fixedClockService.verify(code, RFC_SECRET)).isTrue();
    }

    @Test
    void verifyRejectsWrongCode() {
        assertThat(fixedClockService.verify("000000", RFC_SECRET)).isFalse();
    }

    @Test
    void verifyRejectsExpiredCode() {
        var farFuture = fixedClock.instant().plus(TotpService.TOTP_PERIOD.multipliedBy(3));
        var code = fixedClockService.getTotpCode(RFC_SECRET, farFuture);
        assertThat(fixedClockService.verify(code, RFC_SECRET)).isFalse();
    }

    @Test
    void encryptDecryptRoundTrip() {
        var secret = service.generateSecret();
        var encrypted = service.encryptSecret(secret);
        assertThat(encrypted).isNotEqualTo(secret);
        var decrypted = service.decryptSecret(encrypted);
        assertThat(decrypted).isEqualTo(secret);
    }

    @Test
    void encryptionProducesUniqueOutputs() {
        var secret = service.generateSecret();
        var e1 = service.encryptSecret(secret);
        var e2 = service.encryptSecret(secret);
        assertThat(e1).isNotEqualTo(e2);
    }

    @Test
    void decryptThrowsOnTamperedData() {
        var secret = service.generateSecret();
        var encrypted = service.encryptSecret(secret);
        var tampered = encrypted.substring(0, encrypted.length() - 4) + "XXXX";
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> service.decryptSecret(tampered));
    }

    @Test
    void buildOtpauthUriFormat() {
        var uri = service.buildOtpauthUri("JBSWY3DPEHPK3PXP", "yubai-blog", "admin");
        assertThat(uri).startsWith("otpauth://totp/");
        assertThat(uri).contains("secret=JBSWY3DPEHPK3PXP");
        assertThat(uri).contains("issuer=yubai-blog");
        assertThat(uri).contains("algorithm=SHA1");
        assertThat(uri).contains("digits=6");
        assertThat(uri).contains("period=30");
    }

}
