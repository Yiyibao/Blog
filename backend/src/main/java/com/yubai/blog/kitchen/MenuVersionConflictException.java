package com.yubai.blog.kitchen;

/** FD-10：全量 PUT 的乐观锁冲突——对方刚改过菜单，前端应刷新后重试。 */
public class MenuVersionConflictException extends RuntimeException {
    public MenuVersionConflictException() {
        super("菜单刚被对方更新过，请刷新后再提交");
    }
}
