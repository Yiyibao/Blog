alter table posts
    add column if not exists version bigint not null default 0;

create table post_preview_tokens (
    id uuid primary key,
    post_id bigint not null references posts(id) on delete cascade,
    token_hash varchar(64) not null unique,
    post_version bigint not null,
    expires_at timestamp with time zone not null,
    revoked_at timestamp with time zone,
    created_by varchar(128) not null,
    created_at timestamp with time zone not null default now()
);

create index idx_post_preview_tokens_lookup
    on post_preview_tokens(post_id, revoked_at, expires_at);
