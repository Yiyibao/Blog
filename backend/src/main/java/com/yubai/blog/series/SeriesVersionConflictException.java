package com.yubai.blog.series;

public class SeriesVersionConflictException extends RuntimeException {
    public SeriesVersionConflictException() {
        super("合集已在其他位置更新，请刷新后重试");
    }
}
