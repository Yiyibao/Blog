package com.yubai.blog.sitemap;

import org.springframework.stereotype.Service;

import com.yubai.blog.config.SiteUrlConfig;

@Service
public class RobotsService {

    private final SiteUrlConfig siteUrlConfig;

    public RobotsService(SiteUrlConfig siteUrlConfig) {
        this.siteUrlConfig = siteUrlConfig;
    }

    public String buildRobotsTxt() {
        var base = siteUrlConfig.getSiteUrl();
        return """
            User-agent: *
            Allow: /
            Disallow: /admin
            Disallow: /admin/
            Sitemap: %s/sitemap.xml
            """.formatted(base);
    }
}
