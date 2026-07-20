package com.yubai.blog.project;

import java.util.List;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
    @NotBlank @Size(max = 160) String title,
    @NotBlank String description,
    @NotEmpty List<@NotBlank @Size(max = 80) String> stack,
    @NotBlank @Pattern(regexp = "^\\d{4}$") String year,
    @NotBlank @Size(max = 40) String status,
    @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$") String color,
    @Min(0) int displayOrder
) {
}
