package com.yubai.blog.post;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PostPublicationChecks {
    private PostPublicationChecks() {}

    public static Result evaluate(PostEntity post, Instant scheduledAt) {
        var checks = new ArrayList<Check>();
        if (blank(post.getTitle())) checks.add(error("TITLE_REQUIRED", "标题不能为空"));
        if (blank(post.getSlug())) checks.add(error("SLUG_REQUIRED", "Slug 不能为空"));
        if (blank(post.getExcerpt())) checks.add(error("SUMMARY_REQUIRED", "摘要不能为空"));
        var body =
                post.getContentFormat() == ContentFormat.MARKDOWN
                        ? post.getMarkdownContent()
                        : post.getContent();
        if (blank(body)) checks.add(error("CONTENT_REQUIRED", "正文不能为空"));
        if (scheduledAt != null && !scheduledAt.isAfter(Instant.now())) {
            checks.add(error("SCHEDULE_IN_PAST", "定时发布时间必须晚于当前时间"));
        }
        if (hasEmptyImageAlt(body)) {
            checks.add(error("IMAGE_ALT_REQUIRED", "正文中的图片必须提供非空 alt"));
        }
        if (hasMissingCoverAlt(body)) {
            checks.add(error("COVER_ALT_REQUIRED", "作为封面的首个正文图片必须提供非空 alt"));
        }
        if (hasBrokenLink(body)) {
            checks.add(error("BROKEN_LINK_MARKUP", "正文包含空链接或不完整的链接标记"));
        }
        if (!blank(post.getTitle()) && post.getTitle().trim().length() < 10) {
            checks.add(warning("TITLE_SHORT", "标题较短，建议补充 SEO 语义"));
        }
        if (!blank(post.getExcerpt()) && post.getExcerpt().trim().length() < 30) {
            checks.add(warning("SUMMARY_SHORT", "摘要较短，建议补充搜索摘要"));
        }
        if (!blank(post.getTitle()) && post.getTitle().trim().length() > 70) {
            checks.add(warning("SEO_TITLE_LONG", "标题超过常见 SEO 展示长度，建议缩短"));
        }
        if (!blank(post.getExcerpt()) && post.getExcerpt().trim().length() > 160) {
            checks.add(warning("SEO_DESCRIPTION_LONG", "摘要超过常见 SEO 展示长度，建议缩短"));
        }
        return new Result(
                checks.stream().noneMatch(check -> "ERROR".equals(check.severity())),
                List.copyOf(checks));
    }

    public static void requirePublishable(PostEntity post, Instant scheduledAt) {
        var result = evaluate(post, scheduledAt);
        if (!result.publishable()) throw new PostPublicationException(result);
    }

    private static Check error(String code, String message) {
        return new Check(code, "ERROR", message);
    }

    private static Check warning(String code, String message) {
        return new Check(code, "WARNING", message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static boolean hasEmptyImageAlt(String body) {
        if (blank(body)) return false;
        return body.matches("(?is).*<img\\b(?:(?!\\balt\\s*=\\s*['\"][^'\"]+['\"]).)*>")
                || body.matches("(?s).*!\\[\\s*\\]\\([^)]*\\).*");
    }

    private static boolean hasMissingCoverAlt(String body) {
        if (blank(body)) return false;
        Matcher html = Pattern.compile("(?is)<img\\b[^>]*>").matcher(body);
        if (html.find()) return missingAlt(html.group());
        Matcher markdown = Pattern.compile("(?s)!\\[([^]]*)\\]\\([^)]*\\)").matcher(body);
        return markdown.find() && markdown.group(1).isBlank();
    }

    private static boolean missingAlt(String imageTag) {
        Matcher alt = Pattern.compile("(?is)\\balt\\s*=\\s*(['\"])(.*?)\\1").matcher(imageTag);
        return !alt.find() || alt.group(2).isBlank();
    }

    private static boolean hasBrokenLink(String body) {
        if (blank(body)) return false;
        return body.matches("(?is).*<a\\b(?:(?!\\bhref\\s*=).)*>.*")
                || body.matches("(?is).*\\b(?:href|src)\\s*=\\s*['\"]\\s*['\"]\\s*.*")
                || body.matches("(?s).*\\]\\(\\s*\\).*");
    }

    public record Check(String code, String severity, String message) {}

    public record Result(boolean publishable, List<Check> checks) {}
}
