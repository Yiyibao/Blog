package com.yubai.blog.kitchen;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class ShoppingListDtos {
    private ShoppingListDtos() {}

    public record ItemDraft(
            UUID id,
            @NotBlank @Size(max = 160) String displayName,
            @NotBlank @Size(max = 160) String normalizedName,
            @DecimalMin(value = "0.001") @DecimalMax(value = "999999") BigDecimal quantity,
            @Size(max = 32) String unit,
            @Size(max = 240) String originalQuantity,
            @Size(max = 500) String sourceRecipe,
            @Size(max = 60) String category,
            boolean checked,
            boolean manual,
            @Size(max = 240) String note) {}

    public record UpdateRequest(
            @NotNull Long expectedVersion,
            @Size(max = 500) String note,
            @NotNull @Size(max = 300) List<@Valid ItemDraft> items) {}

    public record ItemResponse(
            UUID id,
            String displayName,
            String normalizedName,
            BigDecimal quantity,
            String unit,
            String originalQuantity,
            String sourceRecipe,
            String category,
            boolean checked,
            boolean manual,
            String note,
            int sortOrder,
            Instant createdAt) {}

    public record ShoppingListResponse(
            UUID id,
            LocalDate weekStart,
            String note,
            long version,
            Instant createdAt,
            Instant updatedAt,
            List<ItemResponse> items) {}
}
