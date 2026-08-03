create table if not exists ai_chat_sessions (
    id bigserial primary key,
    owner varchar(64) not null,
    title varchar(100),
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now()
);

create index if not exists idx_ai_chat_sessions_owner_updated on ai_chat_sessions(owner, updated_at desc);

create table if not exists ai_chat_messages (
    id bigserial primary key,
    session_id bigint not null references ai_chat_sessions(id) on delete cascade,
    role varchar(16) not null,
    content text not null,
    created_at timestamp with time zone not null default now()
);

create index if not exists idx_ai_chat_messages_session on ai_chat_messages(session_id, created_at);
