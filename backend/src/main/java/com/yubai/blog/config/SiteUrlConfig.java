package com.yubai.blog.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SiteUrlConfig {

    private final String siteUrl;

    public SiteUrlConfig(@Value("${app.site-url}") String siteUrl) {
        this.siteUrl = siteUrl.replaceAll("/+$", "");
    }

    public String getSiteUrl() {
        return siteUrl;
    }
}
