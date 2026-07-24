package com.yubai.blog.sitemap;

import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;

import org.springframework.stereotype.Service;

import com.yubai.blog.config.SiteUrlConfig;
import com.yubai.blog.dish.DishRepository;
import com.yubai.blog.note.NoteRepository;
import com.yubai.blog.post.PostRepository;

@Service
public class SitemapService {

    private final SiteUrlConfig siteUrlConfig;
    private final PostRepository postRepository;
    private final DishRepository dishRepository;
    private final NoteRepository noteRepository;

    public SitemapService(SiteUrlConfig siteUrlConfig, PostRepository postRepository,
                          DishRepository dishRepository, NoteRepository noteRepository) {
        this.siteUrlConfig = siteUrlConfig;
        this.postRepository = postRepository;
        this.dishRepository = dishRepository;
        this.noteRepository = noteRepository;
    }

    public String buildSitemapXml() {
        var entries = collectEntries();
        return serializeToXml(entries);
    }

    List<SitemapEntry> collectEntries() {
        var entries = new ArrayList<SitemapEntry>();
        var base = siteUrlConfig.getSiteUrl();

        entries.add(new SitemapEntry(base + "/", null));
        entries.add(new SitemapEntry(base + "/articles", null));
        entries.add(new SitemapEntry(base + "/notes", null));
        entries.add(new SitemapEntry(base + "/recipes", null));
        entries.add(new SitemapEntry(base + "/archive", null));
        entries.add(new SitemapEntry(base + "/about", null));
        entries.add(new SitemapEntry(base + "/categories", null));

        for (var post : postRepository.findPublishedSitemap()) {
            entries.add(new SitemapEntry(
                base + "/articles/" + post.getSlug(),
                post.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE)));
        }

        for (var dish : dishRepository.findPublishedSitemap()) {
            entries.add(new SitemapEntry(
                base + "/recipes?dish=" + dish.getSlug(),
                dish.getUpdatedAt().toString()));
        }

        for (var note : noteRepository.findPublishedSitemap()) {
            entries.add(new SitemapEntry(
                base + "/notes?note=" + note.getId(),
                note.getUpdatedAt().toString()));
        }

        for (var cat : postRepository.findPublishedCategoriesWithCount()) {
            entries.add(new SitemapEntry(
                base + "/categories/" + cat.getCategorySlug(),
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
