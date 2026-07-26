package com.yubai.blog.sitemap;

import org.springframework.stereotype.Service;

import com.yubai.blog.config.SiteUrlConfig;

@Service
public class RobotsService {

    private final SiteUrlConfig siteUrlConfig;

    public RobotsService(SiteUrlConfig siteUrlConfig) {
        this.siteUrlConfig = siteUrlConfig;
    }

    // FD-13：今日菜单是两人私密数据——?view=menu 视图与登录/账号页一并挡爬虫；
    // SitemapService 同理绝不可收录 kitchen 内容
    public String buildRobotsTxt() {
        var base = siteUrlConfig.getSiteUrl();
        return """
            User-agent: *
            Allow: /
            Disallow: /admin
            Disallow: /admin/
            Disallow: /login
            Disallow: /account
            Disallow: /*?*view=menu
            Sitemap: %s/sitemap.xml
            """.formatted(base);
    }
}
