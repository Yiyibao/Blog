package com.yubai.blog.admin;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@RestController
public class SitemapController {

    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final NoteRepository noteRepository;

    public SitemapController(PostRepository postRepository, DishRepository dishRepository, NoteRepository noteRepository) {
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.noteRepository = noteRepository;
    }

    @GetMapping(value = "/sitemap.xml", produces = MediaType.APPLICATION_XML_VALUE)
    @Cacheable("sitemap")
    public String sitemap() {
        var now = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        var url = "https://yubai.dev";
        var sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");

        sb.append(urlEntry(url, now, "daily", "1.0"));
        sb.append(urlEntry(url + "/articles", now, "daily", "0.9"));
        sb.append(urlEntry(url + "/notes", now, "daily", "0.8"));
        sb.append(urlEntry(url + "/recipes", now, "daily", "0.8"));
        sb.append(urlEntry(url + "/about", now, "monthly", "0.5"));

        var posts = postRepository.findAllByOrderByDateDesc(org.springframework.data.domain.PageRequest.of(0, 100));
        posts.forEach(p -> sb.append(urlEntry(url + "/articles/" + p.getSlug(),
            p.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE), "weekly", "0.6")));

        var dishes = dishRepository.findAllByPublishedTrueOrderByFeaturedDescDisplayOrderAsc(
            org.springframework.data.domain.PageRequest.of(0, 100));
        dishes.forEach(d -> sb.append(urlEntry(url + "/recipes?dish=" + d.getSlug(),
            now, "weekly", "0.5")));

        sb.append("</urlset>");
        return sb.toString();
    }

    private String urlEntry(String loc, String lastmod, String changefreq, String priority) {
        return "<url><loc>" + escapeXml(loc) + "</loc><lastmod>" + lastmod
            + "</lastmod><changefreq>" + changefreq + "</changefreq><priority>" + priority + "</priority></url>";
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
