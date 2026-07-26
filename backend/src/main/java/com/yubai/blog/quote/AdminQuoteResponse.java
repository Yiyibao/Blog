package com.yubai.blog.quote;

import java.time.Instant;

/** 4F：管理端语录 DTO——用数据库主键（公开 DTO 的 "q-N" 展示 id 不适合 CRUD 寻址）。 */
public record AdminQuoteResponse(
    long id,
    String content,
    String author,
    String category,
    int displayOrder,
    Instant createdAt
) {
    public static AdminQuoteResponse from(QuoteEntity quote) {
        return new AdminQuoteResponse(
            quote.getId(), quote.getContent(), quote.getAuthor(), quote.getCategory(),
            quote.getDisplayOrder(), quote.getCreatedAt()
        );
    }
}
