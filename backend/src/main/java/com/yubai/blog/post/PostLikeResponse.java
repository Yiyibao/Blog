package com.yubai.blog.post;

public record PostLikeResponse(
    String slug,
    int likeCount
) {
    public static PostLikeResponse from(PostEntity post) {
        return new PostLikeResponse(post.getSlug(), post.getLikeCount());
    }
}
