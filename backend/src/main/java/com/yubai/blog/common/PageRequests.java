package com.yubai.blog.common;

import org.springframework.data.domain.PageRequest;

public final class PageRequests {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 50;

    private PageRequests() {
    }

    public static PageRequest of(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_SIZE));
    }
}
