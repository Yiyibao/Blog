create table ai_projects (
    id bigserial primary key,
    owner varchar(128) not null,
    title varchar(160) not null,
    status varchar(16) not null default 'ACTIVE',
    archived_at timestamp with time zone,
    sort_order integer not null default 0,
    version bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    constraint ck_ai_projects_status check (status in ('ACTIVE', 'ARCHIVED'))
);

create index idx_ai_projects_owner_status_order
    on ai_projects(owner, status, sort_order, updated_at desc);

alter table ai_sessions
    add column project_id bigint,
    add column status varchar(16) not null default 'ACTIVE',
    add column archived_at timestamp with time zone;

alter table ai_sessions
    add constraint ck_ai_sessions_status check (status in ('ACTIVE', 'ARCHIVED', 'DELETED'));

alter table ai_sessions
    add constraint fk_ai_sessions_project
    foreign key (project_id) references ai_projects(id) on delete set null;

create index idx_ai_sessions_owner_project_updated
    on ai_sessions(owner, project_id, updated_at desc);

alter table ai_tasks
    add column requested_provider_id bigint references ai_providers(id) on delete set null,
    add column requested_model varchar(160),
    add column requested_reasoning_effort varchar(16),
    add column resolved_provider_id bigint references ai_providers(id) on delete set null,
    add column resolved_model varchar(160),
    add column resolved_reasoning_effort varchar(16),
    add column required_capabilities varchar(500),
    add column route_reason varchar(500);

create index idx_ai_tasks_owner_requested_route
    on ai_tasks(owner, requested_provider_id, resolved_provider_id, created_at desc);

create table ai_provider_models (
    id bigserial primary key,
    provider_id bigint not null references ai_providers(id) on delete cascade,
    model varchar(160) not null,
    capabilities varchar(1000) not null default 'TEXT',
    reasoning_efforts varchar(160) not null default 'none',
    enabled boolean not null default true,
    version bigint not null default 0,
    created_at timestamp with time zone not null default now(),
    updated_at timestamp with time zone not null default now(),
    constraint uk_ai_provider_models_provider_model unique(provider_id, model),
    constraint ck_ai_provider_models_capabilities check (length(trim(capabilities)) > 0),
    constraint ck_ai_provider_models_reasoning check (length(trim(reasoning_efforts)) > 0)
);

create index idx_ai_provider_models_enabled
    on ai_provider_models(provider_id, enabled, model);
