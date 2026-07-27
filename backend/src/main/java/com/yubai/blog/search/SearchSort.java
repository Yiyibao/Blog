package com.yubai.blog.search;

/** L-8：POST /search 可选排序；5A：新增 RELEVANCE（加权相关性）并作为缺省。 */
public enum SearchSort {
    RELEVANCE,
    DATE_DESC,
    DATE_ASC
}
