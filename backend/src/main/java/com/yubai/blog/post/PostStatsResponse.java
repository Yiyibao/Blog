package com.yubai.blog.post;

public record PostStatsResponse(
    String slug,
    int viewsCount,
    int likeCount
) {
    public static PostStatsResponse from(PostEntity post) {
        return new PostStatsResponse(post.getSlug(), post.getViewsCount(), post.getLikeCount());
    }
}
