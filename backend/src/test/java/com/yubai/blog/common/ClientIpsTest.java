package com.yubai.blog.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * FD-0：转发头只有在请求确实来自可信反代（本机 nginx）时才可信，
 * 否则任何直连后端的客户端都能用一行 header 伪造 IP，绕过全站按 IP 的限流与冷却。
 */
class ClientIpsTest {

    private MockHttpServletRequest request(String remoteAddr) {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void ignoresForwardingHeadersFromUntrustedRemote() {
        var request = request("198.51.100.7");
        request.addHeader("X-Real-IP", "5.6.7.8");
        request.addHeader("X-Forwarded-For", "1.2.3.4");
        assertThat(ClientIps.resolve(request)).as("绕过反代直连时伪造头无效").isEqualTo("198.51.100.7");
    }

    @Test
    void prefersRealIpBehindTrustedProxy() {
        var request = request("127.0.0.1");
        request.addHeader("X-Real-IP", "203.0.113.9");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");
        assertThat(ClientIps.resolve(request))
            .as("X-Real-IP 由 nginx 以 $remote_addr 覆写，优先于客户端可拼接的 XFF")
            .isEqualTo("203.0.113.9");
    }

    @Test
    void usesLastForwardedEntryBehindTrustedProxy() {
        var request = request("::1");
        request.addHeader("X-Forwarded-For", "1.2.3.4, 203.0.113.9");
        assertThat(ClientIps.resolve(request))
            .as("XFF 首位可被客户端预填，末位才是本机 nginx 追加的真实来源")
            .isEqualTo("203.0.113.9");
    }

    @Test
    void fallsBackToRemoteAddrWithoutHeaders() {
        assertThat(ClientIps.resolve(request("127.0.0.1"))).isEqualTo("127.0.0.1");
    }

    @Test
    void ipv6FullFormLoopbackIsTrusted() {
        var request = request("0:0:0:0:0:0:0:1");
        request.addHeader("X-Real-IP", "203.0.113.9");
        assertThat(ClientIps.resolve(request)).isEqualTo("203.0.113.9");
    }
}
