package com.yubai.blog.post;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record PostRequest(
    @NotBlank @Size(max = 120) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
    @NotBlank @Size(max = 200) String title,
    @NotBlank String excerpt,
    @NotNull LocalDate date,
    @Min(1) @Max(180) int readTime,
    @NotBlank @Size(max = 80) String category,
    @NotEmpty List<@NotBlank @Size(max = 80) String> tags,
    @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color,
    @NotBlank @Size(max = 10) String number,
    boolean featured,
    @NotNull PostStatus status,
    @NotBlank String content
) {
}
