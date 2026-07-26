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
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@Service
public class SitemapService {

    private final PublicUrls urls;
    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final NoteRepository noteRepository;

    public SitemapService(PublicUrls urls, PostRepository postRepository,
                          DishRepository dishRepository, NoteRepository noteRepository) {
        this.urls = urls;
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.noteRepository = noteRepository;
    }

    /** P1-5：整份 XML 缓存（TTL 兜底 + admin 写操作 evict），四个全量投影查询不再逐请求执行。 */
    @Cacheable(CacheConfig.SITEMAP)
    public String buildSitemapXml() {
        var entries = collectEntries();
        return serializeToXml(entries);
    }

    List<SitemapEntry> collectEntries() {
        var entries = new ArrayList<SitemapEntry>();

        entries.add(new SitemapEntry(urls.home(), null));
        for (var page : List.of("articles", "notes", "recipes", "archive", "about", "categories")) {
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

        for (var note : noteRepository.findPublishedSitemap()) {
            entries.add(new SitemapEntry(
                urls.note(note.getId()),
                note.getUpdatedAt().toString()));
        }

        for (var cat : postRepository.findPublishedCategoriesWithCount()) {
            entries.add(new SitemapEntry(
                urls.category(cat.getCategorySlug()),
                null));
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
