package com.yubai.blog.sitemap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.yubai.blog.config.SiteUrlConfig;

class RobotsServiceTest {

    SiteUrlConfig siteUrlConfig;
    RobotsService service;

    @BeforeEach
    void setUp() {
        siteUrlConfig = new SiteUrlConfig("https://example.test");
        service = new RobotsService(siteUrlConfig);
    }

    @Test
    void containsUserAgent() {
        var body = service.buildRobotsTxt();
        assertThat(body).contains("User-agent: *");
    }

    @Test
    void allowsRoot() {
        var body = service.buildRobotsTxt();
        assertThat(body).contains("Allow: /");
    }

    @Test
    void disallowsAdmin() {
        var body = service.buildRobotsTxt();
        assertThat(body).contains("Disallow: /admin");
    }

    @Test
    void sitemapUsesConfiguredUrl() {
        var body = service.buildRobotsTxt();
        assertThat(body).contains("Sitemap: https://example.test/sitemap.xml");
    }

    @Test
    void sitemapDoesNotUseHardcodedDomain() {
        var body = service.buildRobotsTxt();
        assertThat(body).doesNotContain("yubai.dev");
        assertThat(body).doesNotContain("localhost");
    }

    @Test
    void publicPagesAreNotDisallowed() {
        var body = service.buildRobotsTxt();
        assertThat(body).doesNotContain("Disallow: /articles");
        assertThat(body).doesNotContain("Disallow: /notes");
        assertThat(body).doesNotContain("Disallow: /recipes");
    }

    @Test
    void noFullSiteDisallow() {
        var body = service.buildRobotsTxt();
        var disallowLines = body.lines()
            .filter(l -> l.startsWith("Disallow:"))
            .toList();
        assertThat(disallowLines).noneMatch(l -> l.equals("Disallow: /"));
    }
}
