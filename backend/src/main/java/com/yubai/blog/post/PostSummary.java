package com.yubai.blog.post;

import java.time.LocalDate;
import java.util.List;

/**
 * P1-2：列表专用摘要 DTO——包含除正文外的全部字段，正文仅由详情接口（PostResponse）返回。
 * 列表、分类聚合等多条场景一律使用本类型，避免整页序列化全文。
 */
public record PostSummary(
    Long id,
    String slug,
    String title,
    String excerpt,
    LocalDate date,
    int readTime,
    String category,
    String categorySlug,
    List<String> tags,
    String color,
    String number,
    boolean featured,
    PostStatus status,
    int likeCount,
    int viewsCount
) {
    static PostSummary from(PostEntity post) {
        return new PostSummary(
            post.getId(), post.getSlug(), post.getTitle(), post.getExcerpt(), post.getDate(),
            post.getReadTime(), post.getCategory(), post.getCategorySlug(), post.getTags(),
            post.getColor(), post.getNumber(), post.isFeatured(), post.getStatus(),
            post.getLikeCount(), post.getViewsCount()
        );
    }
}
