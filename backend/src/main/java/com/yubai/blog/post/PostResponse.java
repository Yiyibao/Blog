package com.yubai.blog.post;

import java.time.LocalDate;
import java.util.List;

public record PostResponse(
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
    String content,
    int likeCount,
    int viewsCount
) {
    /** P1-3：正文在写入路径已消毒入库（PostEntity.create/update），读路径直接返回存储值，不再重复消毒。 */
    static PostResponse from(PostEntity post) {
        return new PostResponse(
            post.getId(), post.getSlug(), post.getTitle(), post.getExcerpt(), post.getDate(), post.getReadTime(),
            post.getCategory(), post.getCategorySlug(), post.getTags(), post.getColor(), post.getNumber(), post.isFeatured(),
            post.getStatus(), post.getContent(), post.getLikeCount(), post.getViewsCount()
        );
    }
}
