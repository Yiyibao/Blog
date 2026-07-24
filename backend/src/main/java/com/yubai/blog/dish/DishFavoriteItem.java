package com.yubai.blog.dish;

public record DishFavoriteItem(
    String slug,
    String name,
    String summary,
    String imageUrl,
    int favoriteCount
) {
    public static DishFavoriteItem from(DishEntity dish) {
        return new DishFavoriteItem(dish.getSlug(), dish.getName(), dish.getSummary(), dish.getImageUrl(), dish.getFavoriteCount());
    }
}
