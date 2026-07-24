package com.yubai.blog.dish;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DishResponse(
    long id,
    String slug,
    String name,
    String summary,
    String category,
    String imageUrl,
    String imageAlt,
    String imageCredit,
    String imageSourceUrl,
    int prepMinutes,
    String difficulty,
    BigDecimal rating,
    boolean featured,
    boolean published,
    int displayOrder,
    int favoriteCount,
    List<String> ingredients,
    List<String> steps,
    Instant createdAt,
    Instant updatedAt
) {
    public static DishResponse from(DishEntity dish) {
        return new DishResponse(
            dish.getId(), dish.getSlug(), dish.getName(), dish.getSummary(), dish.getCategory(),
            dish.getImageUrl(), dish.getImageAlt(), dish.getImageCredit(), dish.getImageSourceUrl(),
            dish.getPrepMinutes(), dish.getDifficulty(), dish.getRating(), dish.isFeatured(), dish.isPublished(),
            dish.getDisplayOrder(), dish.getFavoriteCount(), dish.getIngredients(), dish.getSteps(), dish.getCreatedAt(), dish.getUpdatedAt()
        );
    }
}
