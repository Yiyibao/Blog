package com.yubai.blog.music;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 4F：曲目管理写契约——audio/cover 仅允许 https 外链或站内路径，杜绝再写入占位域名。 */
public record MusicTrackRequest(
    @NotBlank @Size(max = 60) @Pattern(regexp = "^[a-z0-9-]+$") String trackId,
    @NotBlank @Size(max = 200) String title,
    @NotBlank @Size(max = 120) String artist,
    @Min(0) @Max(36000) int duration,
    @NotBlank @Pattern(regexp = "^(https://|/).+", message = "音频地址须为 https 外链或以 / 开头的站内路径") String audioUrl,
    @Pattern(regexp = "^$|^(https://|/).+", message = "封面地址须为 https 外链或以 / 开头的站内路径") String coverUrl,
    @Min(0) @Max(9999) int sortOrder
) {
}
