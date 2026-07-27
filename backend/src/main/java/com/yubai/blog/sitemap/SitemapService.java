package com.yubai.blog.sitemap;

import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.yubai.blog.config.CacheConfig;
import com.yubai.blog.config.PublicUrls;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.series.SeriesRepository;
import com.yubai.blog.series.SeriesStatus;

@Service
public class SitemapService {

    private final PublicUrls urls;
    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final SeriesRepository seriesRepository;

    public SitemapService(PublicUrls urls, PostRepository postRepository,
                          DishRepository dishRepository, SeriesRepository seriesRepository) {
        this.urls = urls;
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.seriesRepository = seriesRepository;
    }

    /** P1-5：整份 XML 缓存（TTL 兜底 + admin 写操作 evict），四个全量投影查询不再逐请求执行。 */
    @Cacheable(CacheConfig.SITEMAP)
    public String buildSitemapXml() {
        var entries = collectEntries();
        return serializeToXml(entries);
    }

    List<SitemapEntry> collectEntries() {
        var entries = new ArrayList<SitemapEntry>();

        // L-16/D-17：学习笔记退出 SEO 收录——/notes 静态页与笔记条目均不再进 sitemap
        entries.add(new SitemapEntry(urls.home(), null));
        for (var page : List.of("articles", "recipes", "archive", "about", "categories")) {
            entries.add(new SitemapEntry(urls.staticPage(page), null));
        }

        for (var post : postRepository.findPublishedSitemap()) {
            entries.add(new SitemapEntry(
                urls.article(post.getSlug()),
                post.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE)));
        }

        for (var dish : dishRepository.findPublishedSitemap()) {
            entries.add(new SitemapEntry(
                urls.recipe(dish.getSlug()),
                dish.getUpdatedAt().toString()));
        }

        // 4B：已发布合集详情页
        for (var series : seriesRepository.findAllByStatusOrderByPublishedAtDesc(SeriesStatus.PUBLISHED)) {
            entries.add(new SitemapEntry(urls.series(series.getSlug()), null));
        }

        for (var cat : postRepository.findPublishedCategoriesWithCount()) {
            entries.add(new SitemapEntry(
                urls.category(cat.getCategorySlug()),
                null));
        }

        // 5B：标签页（已发布文章的标签聚合）
        for (var tag : postRepository.findPublishedTagCounts()) {
            entries.add(new SitemapEntry(urls.tag(tag.getTag()), null));
        }

        return entries;
    }

    private static String serializeToXml(List<SitemapEntry> entries) {
        try {
            var writer = new StringWriter();
            var factory = XMLOutputFactory.newInstance();
            var xml = factory.createXMLStreamWriter(writer);

            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("urlset");
            xml.writeDefaultNamespace("http://www.sitemaps.org/schemas/sitemap/0.9");

            for (var entry : entries) {
                xml.writeStartElement("url");
                xml.writeStartElement("loc");
                xml.writeCharacters(entry.loc());
                xml.writeEndElement();
                if (entry.lastmod() != null) {
                    xml.writeStartElement("lastmod");
                    xml.writeCharacters(entry.lastmod());
                    xml.writeEndElement();
                }
                xml.writeEndElement();
            }

            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            return writer.toString();
        } catch (XMLStreamException e) {
            throw new RuntimeException("Failed to generate sitemap XML", e);
        }
    }
}
