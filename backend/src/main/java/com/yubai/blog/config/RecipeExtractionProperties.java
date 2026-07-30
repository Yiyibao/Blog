package com.yubai.blog.config;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.recipe.extraction")
public record RecipeExtractionProperties(
    boolean videoEnabled,
    String ytDlpPath,
    Duration videoTimeout,
    int maxTranscriptChars,
    List<String> videoHosts
) {
    public RecipeExtractionProperties {
        ytDlpPath = ytDlpPath == null || ytDlpPath.isBlank() ? "yt-dlp" : ytDlpPath.trim();
        videoTimeout = videoTimeout == null ? Duration.ofSeconds(45) : videoTimeout;
        maxTranscriptChars = maxTranscriptChars <= 0 ? 25_000 : maxTranscriptChars;
        videoHosts = videoHosts == null || videoHosts.isEmpty()
            ? List.of(
                "youtube.com", "youtu.be",
                "bilibili.com", "b23.tv",
                "douyin.com", "iesdouyin.com",
                "xiaohongshu.com", "xhslink.com"
            )
            : videoHosts.stream().map(String::trim).map(String::toLowerCase).filter(s -> !s.isBlank()).toList();
    }
}
