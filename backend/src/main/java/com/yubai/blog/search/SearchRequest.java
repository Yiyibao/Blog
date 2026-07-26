package com.yubai.blog.search;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * P2-2/NB-11：移除曾使 @NotNull/@Min 失效的构造器 null 回退——
 * 非法请求（缺 type、page<0、size 越界）现在如实返回 400，而不是被静默修正。
 * L-8：categorySlug 与 sort 为可选契约扩展，仅 POST 类型分支生效；缺省不过滤、最新优先。
 */
public record SearchRequest(
    @NotBlank String query,
    @NotNull SearchType type,
    @Min(0) int page,
    @Min(1) @Max(50) int size,
    String categorySlug,
    SearchSort sort
) {
    public SearchSort sortOrDefault() {
        return sort == null ? SearchSort.DATE_DESC : sort;
    }

    public String categorySlugOrNull() {
        return categorySlug == null || categorySlug.isBlank() ? null : categorySlug;
    }
}
