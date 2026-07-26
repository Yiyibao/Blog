package com.yubai.blog.admin.ai;

import com.yubai.blog.config.AiProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 4A-1：供应商 API 密钥的 AES-256-GCM 加解密。
 * 主密钥来自 APP_AI_MASTER_KEY（.env.properties，不入库不入 git）；
 * 未配置主密钥时注册表的密钥存取整体不可用，绝不降级为明文存储。
 */
@Component
public class AiCrypto {
    private static final String PREFIX = "v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AiCrypto(AiProperties properties) {
        var master = properties.getMasterKey();
        if (master == null || master.isBlank()) {
            this.key = null;
            return;
        }
        if (master.length() < 32) {
            throw new IllegalStateException(
                "APP_AI_MASTER_KEY 至少需要 32 个字符（建议 openssl rand -base64 48 生成）");
        }
        this.key = new SecretKeySpec(sha256(master.getBytes(StandardCharsets.UTF_8)), "AES");
    }

    public boolean isReady() {
        return key != null;
    }

    public String encrypt(String plainText) {
        requireReady();
        try {
            var iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            var cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            var combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);
            return PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException exception) {
            throw new AiServiceException(HttpStatus.INTERNAL_SERVER_ERROR, "AI 密钥加密失败", exception);
        }
    }

    public String decrypt(String storedValue) {
        requireReady();
        if (storedValue == null || !storedValue.startsWith(PREFIX)) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI 密钥密文格式不合法，请重新保存供应商密钥");
        }
        try {
            var combined = Base64.getDecoder().decode(storedValue.substring(PREFIX.length()));
            if (combined.length <= IV_LENGTH) {
                throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE, "AI 密钥密文格式不合法，请重新保存供应商密钥");
            }
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, combined, 0, IV_LENGTH));
            var plain = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                "AI 密钥解密失败（主密钥可能已更换），请重新保存供应商密钥", exception);
        }
    }

    private void requireReady() {
        if (key == null) {
            throw new AiServiceException(HttpStatus.SERVICE_UNAVAILABLE,
                "未配置 APP_AI_MASTER_KEY，无法使用 AI 供应商注册表");
        }
    }

    private static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}
