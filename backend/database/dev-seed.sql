-- Explicit, idempotent development-only seed. Never invoke from Flyway or a
-- production startup path.
insert into posts (
    slug, title, excerpt, published_date, read_time, category, category_slug,
    color, display_number, featured, status, content, markdown_content, content_format
) values (
    'development-welcome', '开发环境示例文章', '仅由显式 dev-seed 命令创建。',
    current_date, 1, '开发示例', '开发示例', '#64748b', 'DEV', false,
    'DRAFT', '<p>Development seed</p>', '# Development seed', 'MARKDOWN'
) on conflict (slug) do nothing;
