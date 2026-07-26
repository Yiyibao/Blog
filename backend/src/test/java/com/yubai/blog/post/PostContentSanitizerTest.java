package com.yubai.blog.post;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PostContentSanitizerTest {

    PostContentSanitizer sanitizer;

    @BeforeEach
    void setUp() {
        sanitizer = new PostContentSanitizer();
    }

    @Test
    void allowsSafeParagraphs() {
        var result = sanitizer.sanitize("<p>Hello</p>");
        assertThat(result).isEqualTo("<p>Hello</p>");
    }

    @Test
    void removesScriptTags() {
        var result = sanitizer.sanitize("<p>safe</p><script>alert(1)</script>");
        assertThat(result).doesNotContain("<script").isEqualTo("<p>safe</p>");
    }

    @Test
    void stripsEventHandlers() {
        var result = sanitizer.sanitize("<a href=\"https://example.com\" onclick=\"alert(1)\">link</a>");
        assertThat(result).doesNotContain("onclick").contains("<a href=\"https://example.com\">link</a>");
    }

    @Test
    void removesJavascriptProtocolInLinks() {
        var result = sanitizer.sanitize("<a href=\"javascript:alert(1)\">xss</a>");
        assertThat(result).doesNotContain("javascript:").doesNotContain("href");
    }

    @Test
    void removesJavascriptProtocolInImages() {
        var result = sanitizer.sanitize("<img src=\"javascript:alert(1)\">");
        assertThat(result).doesNotContain("javascript:").doesNotContain("src=\"");
    }

    @Test
    void stripsOnErrorOnImages() {
        var result = sanitizer.sanitize("<img src=\"https://example.com/x.png\" onerror=\"alert(1)\">");
        assertThat(result).doesNotContain("onerror");
    }

    @Test
    void allowsHeadings() {
        var result = sanitizer.sanitize("<h1>Title</h1><h2>Section</h2><h3>Sub</h3>");
        assertThat(result).contains("<h1>Title</h1>", "<h2>Section</h2>", "<h3>Sub</h3>");
    }

    @Test
    void allowsPreCodeTable() {
        var result = sanitizer.sanitize("<pre><code>code</code></pre><table><tr><td>cell</td></tr></table>");
        assertThat(result).contains("<pre>", "<code>", "<table>", "<td>");
    }

    @Test
    void preservesIdAndClass() {
        var result = sanitizer.sanitize("<p id=\"intro\" class=\"lead\">text</p>");
        assertThat(result).contains("id=\"intro\"", "class=\"lead\"").contains("<p").contains("</p>");
    }

    @Test
    void allowsMailtoLinks() {
        var result = sanitizer.sanitize("<a href=\"mailto:test@example.com\">email</a>");
        assertThat(result).contains("mailto:test@example.com");
    }

    @Test
    void handlesEmptyInput() {
        var result = sanitizer.sanitize("");
        assertThat(result).isEmpty();
    }

    @Test
    void handlesBlankInput() {
        var result = sanitizer.sanitize("   ");
        assertThat(result).isBlank();
    }

    @Test
    void removesUnknownTags() {
        var result = sanitizer.sanitize("<p>text</p><marquee>bad</marquee>");
        assertThat(result).doesNotContain("<marquee>").contains("<p>text</p>");
    }
}
