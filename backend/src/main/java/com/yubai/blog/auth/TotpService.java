package com.yubai.blog.auth;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TotpService {
    static final int TOTP_DIGITS = 6;
    static final Duration TOTP_PERIOD = Duration.ofSeconds(30);
    static final String TOTP_ALGORITHM = "SHA1";
    static final int SECRET_BYTES = 20;
    static final int TIME_STEPS = 1;
    static final String DOMAIN_SEP = "yubai-blog:totp:";
    static final String AES_GCM = "AES/GCM/NoPadding";
    static final int GCM_IV_LEN = 12;
    static final int GCM_TAG_LEN = 128;

    private static final SecureRandom RANDOM = new SecureRandom();
    private final SecretKeySpec encryptionKey;
    private final Clock clock;

    public TotpService(@Value("${app.jwt.secret}") String jwtSecret, Clock clock) {
        this.clock = clock;
        var md = sha256();
        md.update(DOMAIN_SEP.getBytes(StandardCharsets.UTF_8));
        md.update(jwtSecret.getBytes(StandardCharsets.UTF_8));
        var derived = md.digest();
        this.encryptionKey = new SecretKeySpec(derived, "AES");
    }

    public String generateSecret() {
        var bytes = new byte[SECRET_BYTES];
        RANDOM.nextBytes(bytes);
        return Base32.encode(bytes);
    }

    public String getTotpCode(String base32Secret, Instant time) {
        var counter = time.getEpochSecond() / TOTP_PERIOD.toSeconds();
        var hash = hmacSha1(Base32.decode(base32Secret), counter);
        var offset = hash[19] & 0xf;
        int code = ((hash[offset] & 0x7f) << 24)
            | ((hash[offset + 1] & 0xff) << 16)
            | ((hash[offset + 2] & 0xff) << 8)
            | (hash[offset + 3] & 0xff);
        code %= (int) Math.pow(10, TOTP_DIGITS);
        return String.format("%0" + TOTP_DIGITS + "d", code);
    }

    public boolean verify(String code, String base32Secret) {
        var now = clock.instant();
        for (int i = -TIME_STEPS; i <= TIME_STEPS; i++) {
            var expected = getTotpCode(base32Secret, now.plus(TOTP_PERIOD.multipliedBy(i)));
            if (MessageDigest.isEqual(code.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    public String encryptSecret(String plaintext) {
        try {
            var cipher = Cipher.getInstance(AES_GCM);
            var iv = new byte[GCM_IV_LEN];
            RANDOM.nextBytes(iv);
            var spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, spec);
            var ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            var combined = ByteBuffer.allocate(GCM_IV_LEN + ciphertext.length);
            combined.put(iv);
            combined.put(ciphertext);
            return Base64.getEncoder().encodeToString(combined.array());
        } catch (Exception e) {
            throw new IllegalStateException("TOTP secret encryption failed", e);
        }
    }

    public String decryptSecret(String encrypted) {
        try {
            var combined = Base64.getDecoder().decode(encrypted);
            var cipher = Cipher.getInstance(AES_GCM);
            var iv = Arrays.copyOfRange(combined, 0, GCM_IV_LEN);
            var ciphertext = Arrays.copyOfRange(combined, GCM_IV_LEN, combined.length);
            var spec = new GCMParameterSpec(GCM_TAG_LEN, iv);
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey, spec);
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalArgumentException("TOTP secret decryption failed", e);
        }
    }

    public String buildOtpauthUri(String secret, String issuer, String account) {
        var encodedIssuer = encodeUriComponent(issuer);
        var encodedLabel = encodeUriComponent(issuer + ":" + account);
        return String.format(
            "otpauth://totp/%s?secret=%s&issuer=%s&algorithm=%s&digits=%d&period=%d",
            encodedLabel, secret, encodedIssuer, TOTP_ALGORITHM, TOTP_DIGITS, TOTP_PERIOD.toSeconds()
        );
    }

    private static String encodeUriComponent(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static byte[] hmacSha1(byte[] key, long counter) {
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            var counterBytes = ByteBuffer.allocate(8).putLong(counter).array();
            return mac.doFinal(counterBytes);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA1 not available", e);
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
