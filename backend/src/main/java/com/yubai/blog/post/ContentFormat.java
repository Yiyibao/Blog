package com.yubai.blog.post;

/**
 * 3A-1：文章正文格式——HTML 为存量默认，MARKDOWN 为新管线（前端 Tiptap 只读渲染 + DOMPurify 兜底）。
 * 双字段并存期间按篇标记，切换与回退都是改这一列。
 */
public enum ContentFormat {
    HTML,
    MARKDOWN
}
