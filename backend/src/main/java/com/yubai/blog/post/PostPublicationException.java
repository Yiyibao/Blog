package com.yubai.blog.post;

public class PostPublicationException extends RuntimeException {
    private final PostPublicationChecks.Result result;

    public PostPublicationException(PostPublicationChecks.Result result) {
        super("发布前检查未通过");
        this.result = result;
    }

    public PostPublicationChecks.Result getResult() {
        return result;
    }
}
