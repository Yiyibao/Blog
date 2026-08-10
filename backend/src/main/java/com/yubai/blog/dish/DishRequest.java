package com.yubai.blog.dish;

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
import java.math.BigDecimal;
import java.util.List;

public record DishRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Size(max = 1000) String summary,
        @NotBlank @Size(max = 60) String category,
        @NotBlank @Size(max = 1200) String imageUrl,
        @NotBlank @Size(max = 240) String imageAlt,
        @Min(1) @Max(1440) int prepMinutes,
        @NotBlank @Pattern(regexp = "^(简单|家常|进阶)$") String difficulty,
        @NotNull @DecimalMin("0.0") @DecimalMax("5.0") @Digits(integer = 1, fraction = 1)
                BigDecimal rating,
        boolean featured,
        boolean published,
        @Min(0) int displayOrder,
        @Min(1) int baseServings,
        @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 240) String> ingredients,
        @NotEmpty @Size(max = 30) List<@NotBlank @Size(max = 2000) String> steps) {}
