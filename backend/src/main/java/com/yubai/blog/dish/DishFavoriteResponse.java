package com.yubai.blog.dish;

public record DishFavoriteResponse(
    String slug,
    boolean isFavorite,
    int favoriteCount
) {
    public static DishFavoriteResponse from(DishEntity dish, boolean isFavorite) {
        return new DishFavoriteResponse(dish.getSlug(), isFavorite, dish.getFavoriteCount());
    }
}
