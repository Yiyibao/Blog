create table if not exists recipe_extraction_jobs (
    id bigserial primary key,
    source_type varchar(20) not null,
    source_content text not null,
    status varchar(20) not null default 'QUEUED',
    stage varchar(50),
    progress int not null default 0,
    provider_id bigint references ai_providers(id),
    model varchar(120),
    result_import_token uuid,
    safe_error_message varchar(1000),
    attempts int not null default 0,
    created_at timestamp with time zone not null default now(),
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    version int not null default 0
);
