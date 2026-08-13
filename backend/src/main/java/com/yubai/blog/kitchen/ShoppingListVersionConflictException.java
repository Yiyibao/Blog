package com.yubai.blog.kitchen;

public class ShoppingListVersionConflictException extends RuntimeException {
    public ShoppingListVersionConflictException() {
        super("购物清单刚被对方更新过，请刷新后查看差异");
    }
}
