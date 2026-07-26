package com.yubai.blog.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** NB-8：URL builder 单测——各内容类型路径形态与末尾斜杠归一。 */
class PublicUrlsTest {

    private final PublicUrls urls = new PublicUrls(new SiteUrlConfig("https://example.test"));

    @Test
    void buildsCanonicalShapesForEachContentType() {
        assertThat(urls.home()).isEqualTo("https://example.test/");
        assertThat(urls.staticPage("archive")).isEqualTo("https://example.test/archive");
        assertThat(urls.article("clarity-by-design")).isEqualTo("https://example.test/articles/clarity-by-design");
        assertThat(urls.recipe("mapo-tofu")).isEqualTo("https://example.test/recipes?dish=mapo-tofu");
        assertThat(urls.note(42L)).isEqualTo("https://example.test/notes?note=42");
        assertThat(urls.category("engineering")).isEqualTo("https://example.test/categories/engineering");
    }

    @Test
    void trailingSlashesInConfiguredBaseAreNormalized() {
        var slashy = new PublicUrls(new SiteUrlConfig("https://example.test///"));
        assertThat(slashy.article("x")).isEqualTo("https://example.test/articles/x");
        assertThat(slashy.home()).isEqualTo("https://example.test/");
    }
}
