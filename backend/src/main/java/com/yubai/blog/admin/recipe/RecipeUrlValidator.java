package com.yubai.blog.admin.recipe;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.yubai.blog.dish.InvalidRecipeException;

@Component
public class RecipeUrlValidator {

    public URI validatePublicHttps(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new InvalidRecipeException("链接不能为空");
        }
        if (rawUrl.length() > 2048) {
            throw new InvalidRecipeException("URL 长度不能超过 2048 个字符");
        }

        final URI uri;
        try {
            uri = new URI(rawUrl.trim());
        } catch (Exception exception) {
            throw new InvalidRecipeException("URL 格式不合法");
        }

        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new InvalidRecipeException("仅支持公网 HTTPS 链接");
        }
        if (uri.getUserInfo() != null) {
            throw new InvalidRecipeException("URL 不能包含用户凭据");
        }

        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException exception) {
            throw new InvalidRecipeException("无法解析链接域名");
        }
        for (var address : addresses) {
            if (!isPublicAddress(address)) {
                throw new InvalidRecipeException("链接不能指向内网、环回或保留地址");
            }
        }
        return uri;
    }

    public boolean hostMatches(URI uri, Iterable<String> allowedHosts) {
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        for (var allowed : allowedHosts) {
            var normalized = allowed.toLowerCase(Locale.ROOT);
            if (host.equals(normalized) || host.endsWith("." + normalized)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
            || address.isLinkLocalAddress() || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return false;
        }
        var bytes = address.getAddress();
        if (bytes.length == 4) {
            var first = bytes[0] & 0xff;
            var second = bytes[1] & 0xff;
            return first != 0
                && first != 10
                && first != 127
                && !(first == 100 && (second & 0xc0) == 64)
                && !(first == 169 && second == 254)
                && !(first == 172 && second >= 16 && second <= 31)
                && !(first == 192 && second == 168)
                && first < 224;
        }
        return bytes.length != 16 || (bytes[0] & 0xfe) != 0xfc;
    }
}
