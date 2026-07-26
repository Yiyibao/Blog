package com.yubai.blog.sitemap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.yubai.blog.config.SiteUrlConfig;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.post.PostRepository;

@ExtendWith(MockitoExtension.class)
class SitemapServiceTest {

    @Mock
    PostRepository postRepository;

    @Mock
    DishRepository dishRepository;

    SiteUrlConfig siteUrlConfig;
    SitemapService service;

    @BeforeEach
    void setUp() {
        siteUrlConfig = new SiteUrlConfig("https://example.test");
        service = new SitemapService(new com.yubai.blog.config.PublicUrls(siteUrlConfig), postRepository, dishRepository);
    }

    @Test
    void staticPagesAreAlwaysIncluded() {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of());

        var entries = service.collectEntries();

        var locs = entries.stream().map(SitemapEntry::loc).toList();
        assertThat(locs).contains(
            "https://example.test/",
            "https://example.test/articles",
            "https://example.test/categories",
            "https://example.test/recipes",
            "https://example.test/about");
        // L-16/D-17：/notes 静态页退出 sitemap
        assertThat(locs).doesNotContain("https://example.test/notes");
    }

    @Test
    void publishedPostsAreIncluded() {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of(
            new PostRepository.PostSitemapProjection() {
                public String getSlug() { return "test-post"; }
                public LocalDate getDate() { return LocalDate.of(2026, 7, 1); }
            }));
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of());

        var entries = service.collectEntries();

        assertThat(entries).anyMatch(e -> e.loc().equals("https://example.test/articles/test-post"));
        assertThat(entries).anyMatch(e -> e.loc().equals("https://example.test/test-post") == false);
    }

    @Test
    void publishedDishesAreIncluded() {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of(
            new DishRepository.DishSitemapProjection() {
                public String getSlug() { return "test-dish"; }
                public Instant getUpdatedAt() { return Instant.parse("2026-07-15T12:00:00Z"); }
            }));

        var entries = service.collectEntries();

        assertThat(entries).anyMatch(e -> e.loc().contains("/recipes?dish=test-dish"));
    }

    @Test
    void notesAreNeverIncluded() {
        // L-16/D-17：学习笔记退出 SEO 收录——sitemap 不再包含任何笔记 URL
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of());

        var entries = service.collectEntries();

        assertThat(entries).noneMatch(e -> e.loc().endsWith("/notes") || e.loc().contains("/notes?"));
    }

    @Test
    void publishedCategoriesAreIncluded() {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of(
            new PostRepository.CategoryCountProjection() {
                public String getCategory() { return "工程实践"; }
                public String getCategorySlug() { return "e5b7a5e7a88be5ae9ee8b7b5"; }
                public long getCnt() { return 2; }
            }));
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of());

        var entries = service.collectEntries();

        assertThat(entries).anyMatch(e -> e.loc().equals("https://example.test/categories"));
        assertThat(entries).anyMatch(e -> e.loc().equals("https://example.test/categories/e5b7a5e7a88be5ae9ee8b7b5"));
    }

    @Test
    void lastmodUsesEntityTimestamps() {
        var postDate = LocalDate.of(2026, 6, 15);
        var dishTime = Instant.parse("2026-07-10T14:00:00Z");

        when(postRepository.findPublishedSitemap()).thenReturn(List.of(
            new PostRepository.PostSitemapProjection() {
                public String getSlug() { return "p"; }
                public LocalDate getDate() { return postDate; }
            }));
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of(
            new DishRepository.DishSitemapProjection() {
                public String getSlug() { return "d"; }
                public Instant getUpdatedAt() { return dishTime; }
            }));

        var entries = service.collectEntries();

        var postEntry = entries.stream().filter(e -> e.loc().contains("/articles/p")).findFirst().orElseThrow();
        assertThat(postEntry.lastmod()).isEqualTo("2026-06-15");

        var dishEntry = entries.stream().filter(e -> e.loc().contains("/recipes?dish=d")).findFirst().orElseThrow();
        assertThat(dishEntry.lastmod()).isEqualTo("2026-07-10T14:00:00Z");
    }

    @Test
    void staticPagesHaveNoLastmod() {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of());

        var entries = service.collectEntries();

        var staticPages = entries.stream().filter(e ->
            e.loc().equals("https://example.test/") ||
            e.loc().equals("https://example.test/articles") ||
            e.loc().equals("https://example.test/categories") ||
            e.loc().equals("https://example.test/recipes") ||
            e.loc().equals("https://example.test/about")).toList();

        assertThat(staticPages).allMatch(e -> e.lastmod() == null);
    }

    @Test
    void xmLUsesConfiguredSiteRoot() throws Exception {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of());

        var xml = service.buildSitemapXml();

        assertThat(xml).contains("https://example.test/");
        assertThat(xml).doesNotContain("localhost");
        assertThat(xml).doesNotContain("yubai.dev");
    }

    @Test
    void xmlIsValidAndParsable() throws Exception {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of(
            new PostRepository.PostSitemapProjection() {
                public String getSlug() { return "hello"; }
                public LocalDate getDate() { return LocalDate.of(2026, 7, 4); }
            }));
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of(
            new PostRepository.CategoryCountProjection() {
                public String getCategory() { return "工程实践"; }
                public String getCategorySlug() { return "e5b7a5e7a88be5ae9ee8b7b5"; }
                public long getCnt() { return 2; }
            }));
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of(
            new DishRepository.DishSitemapProjection() {
                public String getSlug() { return "sweet-sour"; }
                public Instant getUpdatedAt() { return Instant.parse("2026-07-15T12:00:00Z"); }
            }));

        var xml = service.buildSitemapXml();

        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        var doc = dbf.newDocumentBuilder()
            .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        var root = doc.getDocumentElement();
        assertThat(root.getNamespaceURI()).isEqualTo("http://www.sitemaps.org/schemas/sitemap/0.9");
        assertThat(root.getLocalName()).isEqualTo("urlset");

        var locs = root.getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        assertThat(locs.getLength()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void urlQueryParamsAreCorrectlyEscaped() throws Exception {
        when(postRepository.findPublishedSitemap()).thenReturn(List.of());
        when(postRepository.findPublishedCategoriesWithCount()).thenReturn(List.of());
        when(dishRepository.findPublishedSitemap()).thenReturn(List.of(
            new DishRepository.DishSitemapProjection() {
                public String getSlug() { return "test-dish"; }
                public Instant getUpdatedAt() { return Instant.parse("2026-07-15T12:00:00Z"); }
            }));

        var xml = service.buildSitemapXml();

        var dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        var doc = dbf.newDocumentBuilder()
            .parse(new org.xml.sax.InputSource(new java.io.StringReader(xml)));
        var locs = doc.getDocumentElement()
            .getElementsByTagNameNS("http://www.sitemaps.org/schemas/sitemap/0.9", "loc");
        for (var i = 0; i < locs.getLength(); i++) {
            var val = locs.item(i).getTextContent();
            if (val.contains("?")) {
                assertThat(val).doesNotContain("&amp;");
            }
        }
    }
}
