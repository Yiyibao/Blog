alter table dish_assets alter column storage_key drop not null;

alter table dish_assets add column if not exists content bytea;

alter table dish_assets add constraint ck_dish_assets_content_source
    check (content is not null or storage_key is not null);
