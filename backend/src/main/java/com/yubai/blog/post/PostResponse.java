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
    String markdownContent,
    ContentFormat contentFormat,
    int likeCount,
    int viewsCount,
    PostNeighbor previous,
    PostNeighbor next
) {
    /** 3D：相邻文章导航条目（仅公开详情填充，管理端读写路径为 null）。 */
    public record PostNeighbor(String slug, String title) {
    }

    /** P1-3：正文在写入路径已消毒入库（PostEntity.create/update），读路径直接返回存储值，不再重复消毒。 */
    static PostResponse from(PostEntity post) {
        return from(post, null, null);
    }

    static PostResponse from(PostEntity post, PostNeighbor previous, PostNeighbor next) {
        return new PostResponse(
            post.getId(), post.getSlug(), post.getTitle(), post.getExcerpt(), post.getDate(), post.getReadTime(),
            post.getCategory(), post.getCategorySlug(), post.getTags(), post.getColor(), post.getNumber(), post.isFeatured(),
            post.getStatus(), post.getContent(), post.getMarkdownContent(), post.getContentFormat(),
            post.getLikeCount(), post.getViewsCount(), previous, next
        );
    }
}
