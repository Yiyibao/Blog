package com.yubai.blog.quote;

public record QuoteResponse(
    String id,
    String content,
    String author,
    String category
) {
    public static QuoteResponse from(QuoteEntity entity) {
        return new QuoteResponse(
            "q-" + entity.getId(),
            entity.getContent(),
            entity.getAuthor(),
            entity.getCategory()
        );
    }
}
