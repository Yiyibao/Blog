package com.yubai.blog.post;

public class PostVersionConflictException extends RuntimeException {
    private final long postId;
    private final long expectedVersion;
    private final long actualVersion;
    private final PostResponse server;

    public PostVersionConflictException(
            long postId, long expectedVersion, long actualVersion, PostResponse server) {
        super("文章已在其他位置更新，请查看差异后重试");
        this.postId = postId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.server = server;
    }

    public long getPostId() {
        return postId;
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }

    public PostResponse getServer() {
        return server;
    }
}
