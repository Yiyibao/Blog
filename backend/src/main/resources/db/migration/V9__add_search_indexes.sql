-- Add B-tree indexes on frequently searched and filtered columns
-- to accelerate ORDER BY, equality, and prefix-match queries.
-- Full-text / trigram search (pg_trgm extension) can be added later
-- for LIKE '%keyword%' acceleration when superuser access is available.

create index if not exists idx_posts_status_date
    on posts (status, published_date desc);

create index if not exists idx_posts_slug_status
    on posts (slug, status);

create index if not exists idx_dishes_published_order
    on dishes (published, featured desc, display_order asc);

create index if not exists idx_dishes_slug_published
    on dishes (slug, published);

create index if not exists idx_notes_status_updated
    on learning_notes (status, updated_at desc);

create index if not exists idx_note_attachments_note
    on note_attachments (note_id, created_at desc);

create index if not exists idx_note_attachments_public
    on note_attachments (public_id);

create index if not exists idx_post_tags_post
    on post_tags (post_id);

create index if not exists idx_dish_ingredients_dish
    on dish_ingredients (dish_id);

create index if not exists idx_dish_steps_dish
    on dish_steps (dish_id);
