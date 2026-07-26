package com.yubai.blog.kitchen;

/** FD-10：kitchen 表单级错误（日期格式/菜名缺失等），400 + 中文文案。 */
public class KitchenBadRequestException extends RuntimeException {
    public KitchenBadRequestException(String message) {
        super(message);
    }
}
