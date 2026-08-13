alter table note_attachments
    add column if not exists alt_text varchar(240),
    add column if not exists source_url varchar(500),
    add column if not exists license varchar(160),
    add column if not exists sha256 varchar(64),
    add column if not exists reference_count integer not null default 0,
    add column if not exists created_by varchar(128) not null default 'system:migration';

alter table dish_assets
    add column if not exists alt_text varchar(240),
    add column if not exists source_url varchar(500),
    add column if not exists license varchar(160),
    add column if not exists reference_count integer not null default 0,
    add column if not exists created_by varchar(128) not null default 'system:migration';

alter table ai_generated_images
    add column if not exists alt_text varchar(240),
    add column if not exists source_url varchar(500),
    add column if not exists license varchar(160),
    add column if not exists reference_count integer not null default 0,
    add column if not exists created_by varchar(128) not null default 'system:migration';

alter table ai_artifacts
    add column if not exists alt_text varchar(240),
    add column if not exists source_url varchar(500),
    add column if not exists license varchar(160),
    add column if not exists reference_count integer not null default 0,
    add column if not exists created_by varchar(128) not null default 'system:migration';

update note_attachments
   set alt_text = coalesce(nullif(alt_text, ''), file_name),
       reference_count = 0
 where alt_text is null or reference_count is null;

update dish_assets
   set alt_text = coalesce(nullif(alt_text, ''), file_name),
       reference_count = case when dish_id is null then 0 else 1 end
 where alt_text is null or reference_count is null;

update ai_generated_images
   set alt_text = coalesce(nullif(alt_text, ''), file_name),
       reference_count = 0
 where alt_text is null or reference_count is null;

update ai_artifacts
   set alt_text = coalesce(nullif(alt_text, ''), name),
       reference_count = 0
 where alt_text is null or reference_count is null;

create index if not exists idx_note_attachments_media_hash on note_attachments(sha256);
create index if not exists idx_dish_assets_media_hash on dish_assets(sha256);
create index if not exists idx_ai_generated_images_media_hash on ai_generated_images(sha256);
create index if not exists idx_ai_artifacts_media_hash on ai_artifacts(sha256);
