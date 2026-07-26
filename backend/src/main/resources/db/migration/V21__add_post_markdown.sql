-- 3A-1（v5 阶段 3）：文章 Markdown 化第一步——双字段并存、按篇格式标记，随时可回退。
-- 台账注：V20 预留给美食专项的 dishes.base_servings（NF-12），本迁移按计划 3.3 直接使用 V21，
-- Flyway 对非连续版本号无感。

alter table posts add column markdown_content text;
alter table posts add column content_format varchar(16) not null default 'HTML';
alter table posts add constraint posts_content_format_check
    check (content_format in ('HTML', 'MARKDOWN'));

-- 搜索面同步：markdown 正文进 trgm 索引（与 V18 的 content 索引形态一致，
-- 查询必须保持 lower(col) LIKE lower(?) 形态才受益）
create index if not exists idx_posts_markdown_content_trgm
    on posts using gin (lower(markdown_content) gin_trgm_ops);
