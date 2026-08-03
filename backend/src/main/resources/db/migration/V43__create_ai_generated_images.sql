create table if not exists ai_generated_images (
    id bigserial primary key,
    public_id uuid not null unique,
    provider varchar(32) not null,
    model varchar(120) not null,
    prompt text not null,
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

create index if not exists idx_ai_generated_images_created_at on ai_generated_images(created_at desc);
