package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.yubai.blog.config.AiProperties;
import org.junit.jupiter.api.Test;

class AiCryptoTest {

    private static final String MASTER_KEY = "unit-test-master-key-32-characters-long!";

    private AiCrypto crypto(String masterKey) {
        var properties = new AiProperties();
        properties.setMasterKey(masterKey);
        return new AiCrypto(properties);
    }

    @Test
    void encryptDecryptRoundTrip() {
        var crypto = crypto(MASTER_KEY);
        var cipherText = crypto.encrypt("sk-secret-value-123");
        assertTrue(cipherText.startsWith("v1:"));
        assertFalse(cipherText.contains("sk-secret-value-123"));
        assertEquals("sk-secret-value-123", crypto.decrypt(cipherText));
    }

    @Test
    void sameInputProducesDifferentCipherText() {
        var crypto = crypto(MASTER_KEY);
        assertNotEquals(crypto.encrypt("same"), crypto.encrypt("same"));
    }

    @Test
    void tamperedCipherTextFailsClosed() {
        var crypto = crypto(MASTER_KEY);
        var cipherText = crypto.encrypt("sk-secret");
        var tampered = cipherText.substring(0, cipherText.length() - 2) + "AA";
        var e = assertThrows(AiServiceException.class, () -> crypto.decrypt(tampered));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void differentMasterKeyCannotDecrypt() {
        var cipherText = crypto(MASTER_KEY).encrypt("sk-secret");
        var other = crypto("another-master-key-32-characters-long!!!");
        var e = assertThrows(AiServiceException.class, () -> other.decrypt(cipherText));
        assertEquals(503, e.getStatus().value());
    }

    @Test
    void missingMasterKeyIsNotReadyAndFailsWith503() {
        var crypto = crypto("");
        assertFalse(crypto.isReady());
        var e = assertThrows(AiServiceException.class, () -> crypto.encrypt("sk"));
        assertEquals(503, e.getStatus().value());
        var e2 = assertThrows(AiServiceException.class, () -> crypto.decrypt("v1:abc"));
        assertEquals(503, e2.getStatus().value());
    }

    @Test
    void shortMasterKeyRejectedAtStartup() {
        assertThrows(IllegalStateException.class, () -> crypto("too-short"));
    }

    @Test
    void malformedStoredValueFailsClosed() {
        var crypto = crypto(MASTER_KEY);
        var e = assertThrows(AiServiceException.class, () -> crypto.decrypt("not-prefixed"));
        assertEquals(503, e.getStatus().value());
        var e2 = assertThrows(AiServiceException.class, () -> crypto.decrypt("v1:%%%"));
        assertEquals(503, e2.getStatus().value());
    }
}
