package com.yubai.blog.config;

import org.springframework.stereotype.Component;

/**
 * NB-8：前端公开页 URL 的唯一出处——sitemap 与后续 RSS（3D）共用，
 * 路径形态（/articles/{slug}、/recipes?dish=、/notes?note=）不再散落硬编码。
 */
@Component
public class PublicUrls {

    private final String base;

    public PublicUrls(SiteUrlConfig config) {
        this.base = config.getSiteUrl();
    }

    public String home() { return base + "/"; }

    /** 顶级静态页，如 articles、archive、about。 */
    public String staticPage(String path) { return base + "/" + path; }

    public String article(String slug) { return base + "/articles/" + slug; }

    /** 菜谱详情走查询参数而非路径段——前端路由如此设计。 */
    public String recipe(String slug) { return base + "/recipes?dish=" + slug; }

    public String note(long noteId) { return base + "/notes?note=" + noteId; }

    public String category(String slug) { return base + "/categories/" + slug; }

    /** 4B：合集详情页。 */
    public String series(String slug) { return base + "/series/" + slug; }

    /** 5B：标签页——标签可为中文，路径段按 RFC 3986 转义。 */
    public String tag(String tag) {
        var encoded = java.net.URLEncoder.encode(tag, java.nio.charset.StandardCharsets.UTF_8)
            .replace("+", "%20");
        return base + "/tags/" + encoded;
    }
}
