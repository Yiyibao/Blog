-- FD-6（D1 多用户体系）：admin_users 从"唯一站长"扩展为"站长 + 伴侣"。
-- 角色仅两值 ADMIN/PARTNER，授权直接用 hasAnyRole，不做派生角色。

-- 1) 角色列：先带 default 让存量行合法，再 drop default，
--    避免将来漏传 role 时静默落成 ADMIN（提权型默认值是反模式）。
alter table admin_users add column role varchar(20) not null default 'ADMIN';
alter table admin_users alter column role drop default;
alter table admin_users
    add constraint admin_users_role_check check (role in ('ADMIN', 'PARTNER'));
-- 取值集合与 com.yubai.blog.auth.AdminUserRole 枚举必须同步（枚举类头部有反向注释）。

-- 2) 展示名：菜单/打卡署名用，用户名是登录凭据不该出现在 UI。
alter table admin_users add column display_name varchar(40);
update admin_users set display_name = username where display_name is null;
alter table admin_users alter column display_name set not null;

-- 3) 会话有效起点：JWT 无状态且不可撤销，将来（FD-9）靠比较 jwt.iat >= sessions_valid_from
--    实现"踢下线/改密失效全部旧 token"，本迁移只落列。
alter table admin_users add column sessions_valid_from timestamptz not null default now();

-- 刻意不做：
--   · avatar_emoji —— 两个人用带颜色的名字就够，且 8 字符可塞 RTL override 伪造署名
--   · 单 ADMIN 唯一部分索引 —— 它防的场景需要主动改 .env，却会让 bootstrap 改用户名时撞索引导致启动失败
--   · last_login_at —— 每次登录一次 UPDATE 不值得；审计走登录成功 INFO 日志（FD-0 已加）
