package com.yubai.blog.post;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

/**
 * 3A-1：Markdown 化双字段契约——contentFormat 缺省 HTML（存量调用方无感）； HTML 篇要求 content 非空，MARKDOWN 篇要求
 * markdownContent 非空（content 退化为可选快照）。
 */
public record PostRequest(
        @Size(max = 120) @Pattern(regexp = "^\\s*$|^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
        @NotBlank @Size(max = 200) String title,
        @NotBlank String excerpt,
        @NotNull LocalDate date,
        @Min(1) @Max(180) int readTime,
        @NotBlank @Size(max = 80) String category,
        @NotEmpty List<@NotBlank @Size(max = 80) String> tags,
        @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color,
        @NotBlank @Size(max = 10) String number,
        boolean featured,
        @NotNull PostStatus status,
        String content,
        String markdownContent,
        ContentFormat contentFormat,
        Long version) {
    public PostRequest(
            String slug,
            String title,
            String excerpt,
            LocalDate date,
            int readTime,
            String category,
            List<String> tags,
            String color,
            String number,
            boolean featured,
            PostStatus status,
            String content,
            String markdownContent,
            ContentFormat contentFormat) {
        this(
                slug,
                title,
                excerpt,
                date,
                readTime,
                category,
                tags,
                color,
                number,
                featured,
                status,
                content,
                markdownContent,
                contentFormat,
                null);
    }

    @JsonIgnore
    public ContentFormat contentFormatOrDefault() {
        return contentFormat == null ? ContentFormat.HTML : contentFormat;
    }

    @JsonIgnore
    @AssertTrue(message = "HTML 格式的文章必须提供 content 正文")
    public boolean isHtmlContentPresent() {
        return contentFormatOrDefault() != ContentFormat.HTML
                || (content != null && !content.isBlank());
    }

    @JsonIgnore
    @AssertTrue(message = "MARKDOWN 格式的文章必须提供 markdownContent 正文")
    public boolean isMarkdownContentPresent() {
        return contentFormatOrDefault() != ContentFormat.MARKDOWN
                || (markdownContent != null && !markdownContent.isBlank());
    }
}
