package com.yubai.blog.rss;

import java.io.StringWriter;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.yubai.blog.config.CacheConfig;
import com.yubai.blog.config.PublicUrls;
import com.yubai.blog.post.PostRepository;
import com.yubai.blog.post.PostStatus;

/**
 * 3D：RSS 2.0 feed——仅文章集合（最近 20 篇已发布），URL 统一走 NB-8 的 PublicUrls。
 * 整份 XML 进程内缓存（文章写操作 evict + TTL 兜底），列表查询走 L-12 投影不读正文列。
 */
@Service
@Transactional(readOnly = true)
public class RssService {

    private static final int FEED_SIZE = 20;
    private static final DateTimeFormatter RFC_1123 =
        DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.ENGLISH);

    private final PostRepository postRepository;
    private final PublicUrls urls;

    public RssService(PostRepository postRepository, PublicUrls urls) {
        this.postRepository = postRepository;
        this.urls = urls;
    }

    @Cacheable(CacheConfig.RSS)
    public String buildRssXml() {
        var posts = postRepository.findAllByStatusOrderByDateDesc(
            PostStatus.PUBLISHED, PageRequest.of(0, FEED_SIZE));
        try {
            var writer = new StringWriter();
            var xml = XMLOutputFactory.newInstance().createXMLStreamWriter(writer);
            xml.writeStartDocument("UTF-8", "1.0");
            xml.writeStartElement("rss");
            xml.writeAttribute("version", "2.0");
            xml.writeStartElement("channel");
            text(xml, "title", "余白手记");
            text(xml, "link", urls.home());
            text(xml, "description", "余白 · 数字花园——工程、设计与日常记录");
            text(xml, "language", "zh-cn");

            for (var post : posts) {
                xml.writeStartElement("item");
                text(xml, "title", post.getTitle());
                text(xml, "link", urls.article(post.getSlug()));
                text(xml, "guid", urls.article(post.getSlug()));
                text(xml, "description", post.getExcerpt());
                text(xml, "pubDate", RFC_1123.format(post.getDate().atStartOfDay(ZoneOffset.UTC)));
                text(xml, "category", post.getCategory());
                xml.writeEndElement();
            }

            xml.writeEndElement();
            xml.writeEndElement();
            xml.writeEndDocument();
            xml.flush();
            return writer.toString();
        } catch (XMLStreamException e) {
            throw new IllegalStateException("Failed to generate RSS XML", e);
        }
    }

    private static void text(XMLStreamWriter xml, String tag, String value) throws XMLStreamException {
        xml.writeStartElement(tag);
        xml.writeCharacters(value == null ? "" : value);
        xml.writeEndElement();
    }
}
