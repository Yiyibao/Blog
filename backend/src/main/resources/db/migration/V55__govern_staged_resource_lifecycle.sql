alter table dish_import_staging
    add column owner varchar(128) not null default 'legacy-admin',
    add column byte_size bigint not null default 0;

alter table dish_import_staging
    add constraint ck_dish_import_staging_byte_size check (byte_size >= 0);

create index idx_dish_import_staging_owner_expiry
    on dish_import_staging(owner, expires_at)
    where consumed = false and cancelled = false;

alter table dish_assets
    add column owner varchar(128) not null default 'legacy-admin',
    add column expires_at timestamp with time zone;

update dish_assets
set expires_at = created_at + interval '1 hour'
where dish_id is null and expires_at is null;

create index idx_dish_assets_owner_expiry
    on dish_assets(owner, expires_at)
    where dish_id is null;

-- Existing V51 rows may legitimately retain both the database bytes and their
-- historical storage key. Keep those rows auditable, but forbid any new dual
-- storage or no-storage row without deleting historical data.
alter table dish_assets
    add constraint ck_dish_assets_exactly_one_content_source
    check (num_nonnulls(content, storage_key) = 1) not valid;

create view resource_storage_audit as
select
    'DISH_ASSET'::varchar(32) as resource_type,
    public_id::varchar(64) as resource_id,
    owner,
    case
        when content is not null and storage_key is not null then 'DUAL_STORAGE'
        when content is null and storage_key is null then 'MISSING_STORAGE'
        when dish_id is null and expires_at < now() then 'EXPIRED_UNREFERENCED'
        else 'OK'
    end::varchar(32) as audit_status,
    storage_key,
    byte_size,
    created_at
from dish_assets
union all
select
    'DISH_IMPORT'::varchar(32),
    token::varchar(64),
    owner,
    case
        when storage_key is null then 'MISSING_STORAGE'
        when expires_at < now() and consumed = false and cancelled = false then 'EXPIRED_UNREFERENCED'
        else 'OK'
    end::varchar(32),
    storage_key,
    byte_size,
    created_at
from dish_import_staging;
