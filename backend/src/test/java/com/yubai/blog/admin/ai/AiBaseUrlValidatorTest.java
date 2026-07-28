package com.yubai.blog.admin.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.yubai.blog.config.AiProperties;
import org.junit.jupiter.api.Test;

/**
 * 4A-1 SSRF 校验：全部使用字面量 IP，避免测试依赖真实 DNS。
 */
class AiBaseUrlValidatorTest {

    private AiBaseUrlValidator validator(boolean allowLocal) {
        var properties = new AiProperties();
        properties.setAllowLocalEndpoints(allowLocal);
        return new AiBaseUrlValidator(properties);
    }

    @Test
    void acceptsHttpsPublicAddress() {
        assertEquals("https://93.184.216.34/v1",
            validator(false).validate("https://93.184.216.34/v1/"));
    }

    @Test
    void rejectsPlainHttpForPublicAddress() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validate("http://93.184.216.34"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void rejectsLoopbackByDefault() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validate("https://127.0.0.1:11434"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void rejectsPrivateRangesByDefault() {
        for (var url : new String[]{
            "https://10.0.0.8", "https://192.168.1.20", "https://172.16.5.5", "https://100.64.1.1"}) {
            var e = assertThrows(AiServiceException.class, () -> validator(false).validate(url), url);
            assertEquals(400, e.getStatus().value());
        }
    }

    @Test
    void allowLocalFlagPermitsLoopbackAndPrivateIncludingHttp() {
        var validator = validator(true);
        assertEquals("http://127.0.0.1:11434", validator.validate("http://127.0.0.1:11434/"));
        assertEquals("https://192.168.1.20", validator.validate("https://192.168.1.20"));
    }

    @Test
    void linkLocalAlwaysRejectedEvenWithLocalFlag() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(true).validate("https://169.254.169.254"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void anyLocalAddressAlwaysRejected() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(true).validate("https://0.0.0.0"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void rejectsUnsupportedSchemeAndMalformedUrl() {
        assertEquals(400, assertThrows(AiServiceException.class,
            () -> validator(false).validate("ftp://example.com")).getStatus().value());
        assertEquals(400, assertThrows(AiServiceException.class,
            () -> validator(false).validate("https://")).getStatus().value());
        assertEquals(400, assertThrows(AiServiceException.class,
            () -> validator(false).validate("   ")).getStatus().value());
    }

    @Test
    void httpForPublicStillRejectedWithLocalFlag() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(true).validate("http://93.184.216.34"));
        assertEquals(400, e.getStatus().value());
    }

    // ===== OPENCODE_SERVER 专用校验 =====

    @Test
    void opencodeServerAcceptsHttpLoopback() {
        assertEquals("http://127.0.0.1:8080",
            validator(false).validateForOpenCodeServer("http://127.0.0.1:8080"));
    }

    @Test
    void opencodeServerAcceptsHttpsLoopback() {
        assertEquals("https://localhost:8443",
            validator(false).validateForOpenCodeServer("https://localhost:8443"));
    }

    @Test
    void opencodeServerRejectsPublicAddress() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("https://93.184.216.34"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsPrivateNonLoopback() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("https://192.168.1.1"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsUserinfo() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("http://user:pass@127.0.0.1:8080"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsQuery() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("http://127.0.0.1:8080?foo=bar"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsFragment() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("http://127.0.0.1:8080#frag"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsUnsupportedScheme() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("ftp://127.0.0.1"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsBlank() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("   "));
        assertEquals(400, e.getStatus().value());
    }

    // ===== DNS rebinding & path hardening =====

    @Test
    void opencodeServerRejectsArbitraryHostname() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("https://evil-rebind.example.com"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsNonLoopbackHostname() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("https://example.com"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerRejectsPath() {
        var e = assertThrows(AiServiceException.class,
            () -> validator(false).validateForOpenCodeServer("http://127.0.0.1:8080/some/path"));
        assertEquals(400, e.getStatus().value());
    }

    @Test
    void opencodeServerAcceptsRootPath() {
        assertEquals("http://127.0.0.1:8080",
            validator(false).validateForOpenCodeServer("http://127.0.0.1:8080/"));
    }

    @Test
    void isAllowedOpenCodeHostRejectsRandomName() {
        assertFalse(AiBaseUrlValidator.isAllowedOpenCodeHost("attacker.com"));
        assertFalse(AiBaseUrlValidator.isAllowedOpenCodeHost("localhost-evil"));
    }

    @Test
    void isAllowedOpenCodeHostAcceptsKnownValues() {
        assertTrue(AiBaseUrlValidator.isAllowedOpenCodeHost("localhost"));
        assertTrue(AiBaseUrlValidator.isAllowedOpenCodeHost("LOCALHOST"));
        assertTrue(AiBaseUrlValidator.isAllowedOpenCodeHost("127.0.0.1"));
        assertTrue(AiBaseUrlValidator.isAllowedOpenCodeHost("::1"));
        assertTrue(AiBaseUrlValidator.isAllowedOpenCodeHost("0:0:0:0:0:0:0:1"));
    }
}
