package com.yubai.blog.dish;

/**
 * P0-7（已批准）：收藏为纯计数语义，不再返回名不副实的 isFavorite
 * （旧实现只增不减且恒返回 true）。
 */
public record DishFavoriteResponse(
    String slug,
    int favoriteCount
) {
    public static DishFavoriteResponse from(DishEntity dish) {
        return new DishFavoriteResponse(dish.getSlug(), dish.getFavoriteCount());
    }
}
