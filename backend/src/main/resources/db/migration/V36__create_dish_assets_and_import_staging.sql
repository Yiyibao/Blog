create table if not exists dish_assets (
    id bigserial primary key,
    public_id uuid not null unique,
    dish_id bigint references dishes(id) on delete cascade,
    storage_key varchar(512) not null,
    file_name varchar(255) not null,
    media_type varchar(100) not null,
    byte_size bigint not null,
    sha256 varchar(64) not null,
    width int,
    height int,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now()
);

create unique index if not exists idx_dish_assets_dish_id on dish_assets(dish_id) where dish_id is not null;

create table if not exists dish_import_staging (
    id bigserial primary key,
    token uuid not null unique,
    recipe_json text not null,
    storage_key varchar(512),
    media_type varchar(100),
    consumed boolean not null default false,
    cancelled boolean not null default false,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null default now()
);

alter table dish_import_staging add column if not exists consumed boolean not null default false;
alter table dish_import_staging add column if not exists cancelled boolean not null default false;
alter table dish_import_staging add column if not exists version int not null default 0;
