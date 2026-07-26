-- P1-4/NB-1（v5 阶段 1）：pg_trgm 三元组 GIN 索引加速三表 LIKE '%..%' 搜索，
-- 以及 posts 分类过滤复合索引。查询保持 LOWER(col) LIKE LOWER(?) 形态即可受益，
-- 故索引表达式必须与之一致（lower(col) + gin_trgm_ops）。
-- pg_trgm 自 PG13 起为 trusted 扩展：库 owner（yubai_app）即可安装，无需超级用户。
-- 注：V17 由并行专项（FD 第二账号）占用；tag/食材/步骤等子表已有 FK B-tree（V9），
-- 其行短量小，trigram 收益有限，暂不建，观察后再议。

create extension if not exists pg_trgm;

-- posts 搜索列（PostRepository.searchPublished：title/excerpt/category/content）
create index if not exists idx_posts_title_trgm
    on posts using gin (lower(title) gin_trgm_ops);
create index if not exists idx_posts_excerpt_trgm
    on posts using gin (lower(excerpt) gin_trgm_ops);
create index if not exists idx_posts_category_trgm
    on posts using gin (lower(category) gin_trgm_ops);
create index if not exists idx_posts_content_trgm
    on posts using gin (lower(content) gin_trgm_ops);

-- learning_notes 搜索列（NoteRepository.searchPublished：title/folder/markdown_content）
create index if not exists idx_notes_title_trgm
    on learning_notes using gin (lower(title) gin_trgm_ops);
create index if not exists idx_notes_folder_trgm
    on learning_notes using gin (lower(folder) gin_trgm_ops);
create index if not exists idx_notes_content_trgm
    on learning_notes using gin (lower(markdown_content) gin_trgm_ops);

-- dishes 搜索列（DishRepository.searchPublished：name/summary/category）
create index if not exists idx_dishes_name_trgm
    on dishes using gin (lower(name) gin_trgm_ops);
create index if not exists idx_dishes_summary_trgm
    on dishes using gin (lower(summary) gin_trgm_ops);
create index if not exists idx_dishes_category_trgm
    on dishes using gin (lower(category) gin_trgm_ops);

-- NB-1：分类页与分类过滤列表（PostRepository.findByCategorySlugAndStatus*）
create index if not exists idx_posts_category_slug_status
    on posts (category_slug, status);
