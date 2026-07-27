create table refresh_tokens (
    id bigserial primary key,
    token_hash varchar(64) not null,
    user_id bigint not null references admin_users(id) on delete cascade,
    family uuid not null,
    expires_at timestamp with time zone not null,
    created_at timestamp with time zone not null default now(),
    last_used_at timestamp with time zone,
    revoked boolean not null default false
);

create unique index uq_refresh_tokens_hash on refresh_tokens(token_hash);
create index idx_refresh_tokens_family on refresh_tokens(family);
create index idx_refresh_tokens_user_id on refresh_tokens(user_id);
