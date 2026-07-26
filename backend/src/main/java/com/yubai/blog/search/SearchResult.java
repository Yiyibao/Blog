package com.yubai.blog.search;

/** L-8：date/readTime/tags 仅在 POST 分页分支实装（文章头展示所需），其余类型为 null。 */
public record SearchResult(
    String type,
    long id,
    String title,
    String excerpt,
    String category,
    String url,
    String color,
    String number,
    String slug,
    String date,
    Integer readTime,
    java.util.List<String> tags
) {
}
