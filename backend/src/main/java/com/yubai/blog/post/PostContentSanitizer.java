package com.yubai.blog.post;

import org.jsoup.Jsoup;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

@Component
public class PostContentSanitizer {
    private final Safelist policy = Safelist.relaxed()
        .addTags("h1", "h2", "h3", "pre", "code", "table", "thead", "tbody", "tr", "th", "td")
        .addAttributes(":all", "id", "class")
        .addProtocols("a", "href", "http", "https", "mailto")
        .addProtocols("img", "src", "http", "https");

    public String sanitize(String html) {
        return Jsoup.clean(html, "", policy);
    }
}
