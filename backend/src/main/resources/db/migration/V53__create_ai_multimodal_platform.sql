create table ai_sessions (
    id bigserial primary key,
    owner varchar(128) not null,
    title varchar(160),
    mode varchar(32) not null default 'WORKSPACE',
    summary text,
    version bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now()
);

create index idx_ai_sessions_owner_updated
    on ai_sessions(owner, updated_at desc);

create table ai_tasks (
    id uuid primary key,
    owner varchar(128) not null,
    session_id bigint not null references ai_sessions(id) on delete cascade,
    task_type varchar(40) not null,
    status varchar(32) not null,
    provider_id bigint references ai_providers(id) on delete set null,
    provider_type varchar(32),
    model varchar(160),
    idempotency_key varchar(160) not null,
    started_at timestamp with time zone,
    finished_at timestamp with time zone,
    error_code varchar(80),
    error_message varchar(500),
    version bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    constraint uk_ai_tasks_owner_idempotency unique(owner, idempotency_key),
    constraint ck_ai_tasks_status check (status in (
        'QUEUED', 'RUNNING', 'WAITING_APPROVAL', 'COMPLETED', 'FAILED', 'CANCELLED'
    ))
);

create index idx_ai_tasks_owner_created on ai_tasks(owner, created_at desc);
create index idx_ai_tasks_session_created on ai_tasks(session_id, created_at);
create index idx_ai_tasks_status_updated on ai_tasks(status, updated_at);

create table ai_files (
    id uuid primary key,
    owner varchar(128) not null,
    storage_key varchar(512) not null unique,
    original_name varchar(255) not null,
    media_type varchar(120) not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    status varchar(24) not null,
    retention varchar(24) not null,
    expires_at timestamp with time zone,
    reference_count integer not null default 0,
    extracted_text text,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    constraint ck_ai_files_size check (size_bytes >= 0),
    constraint ck_ai_files_reference_count check (reference_count >= 0),
    constraint ck_ai_files_status check (status in (
        'UPLOADED', 'VALIDATING', 'READY', 'REJECTED', 'EXPIRED', 'DELETED'
    ))
);

create index idx_ai_files_owner_created on ai_files(owner, created_at desc);
create index idx_ai_files_expiry on ai_files(status, expires_at);

create table ai_artifacts (
    id uuid primary key,
    owner varchar(128) not null,
    task_id uuid not null references ai_tasks(id) on delete cascade,
    storage_key varchar(512) not null unique,
    name varchar(255) not null,
    media_type varchar(120) not null,
    size_bytes bigint not null,
    sha256 varchar(64) not null,
    status varchar(24) not null,
    expires_at timestamp with time zone,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    constraint uk_ai_artifacts_task_name unique(task_id, name),
    constraint ck_ai_artifacts_size check (size_bytes >= 0),
    constraint ck_ai_artifacts_status check (status in (
        'PENDING', 'READY', 'FAILED', 'EXPIRED', 'DELETED'
    ))
);

create index idx_ai_artifacts_owner_created on ai_artifacts(owner, created_at desc);
create index idx_ai_artifacts_expiry on ai_artifacts(status, expires_at);

create table ai_task_parts (
    id bigserial primary key,
    task_id uuid not null references ai_tasks(id) on delete cascade,
    sequence integer not null,
    role varchar(24) not null,
    kind varchar(32) not null,
    text_content text,
    payload text,
    file_id uuid references ai_files(id) on delete set null,
    artifact_id uuid references ai_artifacts(id) on delete set null,
    source_ref varchar(500),
    created_at timestamp with time zone not null default now(),
    constraint uk_ai_task_parts_sequence unique(task_id, sequence),
    constraint ck_ai_task_parts_role check (role in ('SYSTEM', 'USER', 'ASSISTANT', 'TOOL')),
    constraint ck_ai_task_parts_kind check (kind in (
        'TEXT', 'IMAGE_REF', 'FILE_REF', 'ARTIFACT_REF', 'TOOL_CALL', 'TOOL_RESULT', 'SOURCE_REF'
    ))
);

create index idx_ai_task_parts_file on ai_task_parts(file_id) where file_id is not null;

create table ai_task_events (
    id bigserial primary key,
    task_id uuid not null references ai_tasks(id) on delete cascade,
    sequence bigint not null,
    event_type varchar(80) not null,
    sanitized_payload text not null default '{}',
    created_at timestamp with time zone not null default now(),
    constraint uk_ai_task_events_sequence unique(task_id, sequence)
);

create index idx_ai_task_events_replay on ai_task_events(task_id, sequence);

create table ai_memories (
    id uuid primary key,
    owner varchar(128) not null,
    scope varchar(80) not null,
    kind varchar(40) not null,
    content text,
    source_task_id uuid references ai_tasks(id) on delete set null,
    source_ref varchar(500),
    status varchar(24) not null,
    confidence numeric(5,4),
    expires_at timestamp with time zone,
    version bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    constraint ck_ai_memories_status check (status in (
        'PROPOSED', 'ACTIVE', 'REJECTED', 'DISABLED', 'DELETED'
    )),
    constraint ck_ai_memories_confidence check (
        confidence is null or (confidence >= 0 and confidence <= 1)
    )
);

create index idx_ai_memories_owner_status on ai_memories(owner, status, updated_at desc);

do $$
begin
    if not exists (
        select 1 from pg_constraint where conname = 'fk_ai_generated_images_session'
    ) then
        alter table ai_generated_images
            add constraint fk_ai_generated_images_session
            foreign key (session_id) references ai_image_sessions(id) on delete cascade;
    end if;
end $$;
