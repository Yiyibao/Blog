package com.yubai.blog.post;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3A-2：存量文章一次性转换编排——只回填 markdown_content，不改 content 与 content_format
 * （读路径切换是 3A-5 校对签收后的独立动作，随时可回退）。幂等：已有 markdown 的篇默认跳过。
 */
@Service
public class PostMarkdownConversionService {

    private static final Logger log = LoggerFactory.getLogger(PostMarkdownConversionService.class);

    public record ConversionReport(long id, String slug, boolean converted, List<String> risks) {
    }

    private final PostRepository repository;
    private final HtmlToMarkdownConverter converter;

    public PostMarkdownConversionService(PostRepository repository, HtmlToMarkdownConverter converter) {
        this.repository = repository;
        this.converter = converter;
    }

    @Transactional
    public List<ConversionReport> convertAll(boolean force) {
        var reports = new ArrayList<ConversionReport>();
        for (var post : repository.findAll()) {
            if (post.getContentFormat() == ContentFormat.MARKDOWN) {
                reports.add(new ConversionReport(post.getId(), post.getSlug(), false, List.of("已是 MARKDOWN 格式，跳过")));
                continue;
            }
            if (post.getMarkdownContent() != null && !force) {
                reports.add(new ConversionReport(post.getId(), post.getSlug(), false, List.of("已有 markdown 草稿，跳过（force=true 可覆盖）")));
                continue;
            }
            var conversion = converter.convert(post.getContent());
            post.applyMarkdownConversion(conversion.markdown(), ContentFormat.HTML);
            reports.add(new ConversionReport(post.getId(), post.getSlug(), true, conversion.risks()));
            log.info("3A-2 converted post {} ({}), risks={}", post.getId(), post.getSlug(), conversion.risks().size());
        }
        return reports;
    }
}
