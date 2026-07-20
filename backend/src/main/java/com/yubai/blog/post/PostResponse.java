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
    List<String> tags,
    String color,
    String number,
    boolean featured,
    String content
) {
    static PostResponse from(PostEntity post) {
        return new PostResponse(
            post.getId(), post.getSlug(), post.getTitle(), post.getExcerpt(), post.getDate(), post.getReadTime(),
            post.getCategory(), post.getTags(), post.getColor(), post.getNumber(), post.isFeatured(), post.getContent()
        );
    }
}
