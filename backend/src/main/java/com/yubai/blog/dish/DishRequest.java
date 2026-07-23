package com.yubai.blog.dish;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DishRequest(
    @NotBlank @Size(max = 120) @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$") String slug,
    @NotBlank @Size(max = 120) String name,
    @NotBlank @Size(max = 1000) String summary,
    @NotBlank @Size(max = 60) String category,
    @NotBlank @Size(max = 1200) String imageUrl,
    @NotBlank @Size(max = 240) String imageAlt,
    @NotBlank @Size(max = 240) String imageCredit,
    @NotBlank @Size(max = 1200) String imageSourceUrl,
    @Min(1) @Max(1440) int prepMinutes,
    @NotBlank @Pattern(regexp = "^(简单|家常|进阶)$") String difficulty,
    @NotNull @DecimalMin("0.0") @DecimalMax("5.0") @Digits(integer = 1, fraction = 1) BigDecimal rating,
    boolean featured,
    boolean published,
    @Min(0) int displayOrder,
    @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 240) String> ingredients,
    @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 2000) String> steps
) {
}
