package com.yubai.blog.rss;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RssController {

    private final RssService service;

    public RssController(RssService service) {
        this.service = service;
    }

    /** 3D：RSS feed——安全链白名单放行，形态与 /sitemap.xml 对齐。 */
    @GetMapping(value = "/rss.xml", produces = "application/rss+xml;charset=UTF-8")
    public ResponseEntity<String> rss() {
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/rss+xml;charset=UTF-8"))
            .body(service.buildRssXml());
    }
}
