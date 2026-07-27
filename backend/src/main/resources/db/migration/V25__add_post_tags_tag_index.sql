-- 5B：标签一等公民——按标签检索文章的函数索引（查询侧统一 lower() 等值匹配）
create index post_tags_tag_idx on post_tags (lower(tag));
