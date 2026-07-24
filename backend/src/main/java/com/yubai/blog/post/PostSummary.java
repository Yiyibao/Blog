package com.yubai.blog.post;

import java.time.LocalDate;
import java.util.List;

public record PostSummary(
    String slug,
    String title,
    String excerpt,
    LocalDate date,
    List<String> tags
) {
    static PostSummary from(PostEntity post) {
        return new PostSummary(
            post.getSlug(), post.getTitle(), post.getExcerpt(), post.getDate(),
            post.getTags()
        );
    }
}
