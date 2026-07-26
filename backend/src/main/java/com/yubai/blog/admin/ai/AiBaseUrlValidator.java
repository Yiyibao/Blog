package com.yubai.blog.admin.ai;

import com.yubai.blog.config.AiProperties;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 4A-1 SSRF 防护：base_url 可由管理界面配置，是新攻击面。
 * 规则：仅 https；创建/修改时解析 DNS 并拒绝私网、环回地址；
 * 本地供应商（如服务器上的 Ollama）需 APP_AI_ALLOW_LOCAL_ENDPOINTS=true（只能改 env 重启生效），
 * 该开关也只放开环回与私网——链路本地（含云元数据 169.254.0.0/16）永远拒绝。
 */
@Component
public class AiBaseUrlValidator {
    private final AiProperties properties;

    public AiBaseUrlValidator(AiProperties properties) {
        this.properties = properties;
    }

    /**
     * @return 规范化后的 base_url（去掉末尾斜杠）
     * @throws AiServiceException 400 校验失败
     */
    public String validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw badRequest("base_url 不能为空");
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.trim());
        } catch (IllegalArgumentException exception) {
            throw badRequest("base_url 不是合法的 URL");
        }
        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (scheme == null || host == null || host.isBlank()) {
            throw badRequest("base_url 必须是含协议与主机名的绝对地址");
        }
        var lowerScheme = scheme.toLowerCase();
        if (!lowerScheme.equals("https") && !lowerScheme.equals("http")) {
            throw badRequest("base_url 仅支持 https（本地端点可用 http）");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw badRequest("无法解析 base_url 的主机名");
        }

        var allLocal = true;
        for (var address : addresses) {
            if (address.isLinkLocalAddress() || address.isAnyLocalAddress() || address.isMulticastAddress()) {
                throw badRequest("base_url 禁止指向链路本地或保留地址");
            }
            var local = address.isLoopbackAddress() || address.isSiteLocalAddress()
                || isUniqueLocalIpv6(address) || isCarrierGradeNat(address);
            if (local && !properties.isAllowLocalEndpoints()) {
                throw badRequest("base_url 禁止指向内网/环回地址；如需本地模型服务，"
                    + "请设置 APP_AI_ALLOW_LOCAL_ENDPOINTS=true 并重启后端");
            }
            allLocal = allLocal && local;
        }

        if (lowerScheme.equals("http") && !(properties.isAllowLocalEndpoints() && allLocal)) {
            throw badRequest("http 仅允许用于已放开的本地端点，公网地址必须使用 https");
        }

        return rawUrl.trim().replaceAll("/+$", "");
    }

    private static boolean isUniqueLocalIpv6(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }

    private static boolean isCarrierGradeNat(InetAddress address) {
        var bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 64;
    }

    private static AiServiceException badRequest(String message) {
        return new AiServiceException(HttpStatus.BAD_REQUEST, message);
    }
}
